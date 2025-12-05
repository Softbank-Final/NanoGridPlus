package org.brown.nanogridplus.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.brown.nanogridplus.metrics.AutoTunerService;
import org.brown.nanogridplus.metrics.CloudWatchMetricsPublisher;
import org.brown.nanogridplus.metrics.ResourceMonitor;
import org.brown.nanogridplus.model.ExecutionResult;
import org.brown.nanogridplus.model.TaskMessage;
import org.brown.nanogridplus.s3.OutputFileUploader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Docker Engine을 사용한 컨테이너 실행 서비스
 *
 * 현재 단계 (5~6단계):
 * - Warm Pool에서 컨테이너 재사용
 * - docker exec로 코드 실행
 * - Pause/Unpause로 Cold Start 제거
 * - Auto-Tuner 통합 (메모리 측정 + CloudWatch 전송 + 최적화 팁)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.warmPool.enabled", havingValue = "true", matchIfMissing = true)
public class DockerEngineService implements DockerService {

    private final DockerClient dockerClient;
    private final AgentProperties agentProperties;
    private final WarmPoolManager warmPoolManager;
    private final ResourceMonitor resourceMonitor;
    private final CloudWatchMetricsPublisher metricsPublisher;
    private final AutoTunerService autoTunerService;
    private final OutputFileUploader outputFileUploader;

    @Override
    public ExecutionResult runTask(TaskMessage taskMessage, Path workDir) {
        String requestId = taskMessage.getRequestId();
        String functionId = taskMessage.getFunctionId();
        String runtime = taskMessage.getRuntime();

        log.info("Starting Warm Pool execution for request: {}, runtime: {}", requestId, runtime);

        // RuntimeType 결정
        WarmPoolManager.RuntimeType runtimeType = resolveRuntimeType(taskMessage);
        String containerId = null;
        long startTime = System.currentTimeMillis();

        try {
            // 1. Warm Pool에서 컨테이너 획득 (unpause 완료)
            containerId = warmPoolManager.acquireContainer(runtimeType);
            log.info("Acquired container: {} from Warm Pool for request: {}", containerId, requestId);

            // 2. Output 디렉터리 생성 (호스트 측)
            String outputHostPath = createOutputDirectory(requestId);
            log.debug("Created output directory: {}", outputHostPath);

            // 3. 컨테이너 내부 작업 디렉터리 경로 설정
            String containerWorkDir = agentProperties.getDocker().getWorkDirRoot() + "/" + requestId;
            log.debug("Container work dir: {}", containerWorkDir);

            // 4. 런타임별 실행 커맨드 구성
            List<String> cmd = buildCommandForRuntime(taskMessage, containerWorkDir);
            log.info("Executing command in container {}: {}", containerId, cmd);

            // TODO: Auto-Tuner hook - 실행 전 메트릭 수집 시작

            // 4. docker exec로 명령 실행
            // 4. docker exec로 명령 실행
            ExecResult execResult = executeInContainer(containerId, containerWorkDir, cmd);

            long endTime = System.currentTimeMillis();
            long durationMillis = endTime - startTime;

            // 5. Auto-Tuner: 메모리 측정 및 CloudWatch 전송
            Long peakMemoryBytes = null;
            String optimizationTip = null;
            try {
                log.debug("Measuring peak memory for container: {}", containerId);
                peakMemoryBytes = resourceMonitor.measurePeakMemoryBytes(containerId);
                log.info("Measured peak memory: {} bytes", peakMemoryBytes);

                // CloudWatch에 메트릭 전송
                metricsPublisher.publishPeakMemory(functionId, runtime, peakMemoryBytes);

                // 최적화 팁 생성
                optimizationTip = autoTunerService.createOptimizationTip(taskMessage, peakMemoryBytes);

            } catch (Exception e) {
                log.warn("Auto-Tuner failed for request {} (container={}), continuing without metrics",
                        requestId, containerId, e);
            }

            log.info("Container {} exec finished with exitCode: {} in {}ms",
                    containerId, execResult.exitCode, durationMillis);

            // 6. Output Binding: 생성된 파일을 S3에 업로드
            List<String> outputFiles = List.of();
            try {
                log.debug("Uploading output files for request: {}", requestId);
                outputFiles = outputFileUploader.uploadOutputFiles(requestId, containerId);
                if (!outputFiles.isEmpty()) {
                    log.info("📦 [OUTPUT] Uploaded {} file(s) for requestId={}", outputFiles.size(), requestId);
                }
            } catch (Exception e) {
                log.warn("Failed to upload output files for request: {}, continuing", requestId, e);
            }

            // 7. ExecutionResult 생성
            return ExecutionResult.builder()
                    .requestId(requestId)
                    .functionId(functionId)
                    .exitCode(execResult.exitCode)
                    .stdout(execResult.stdout)
                    .stderr(execResult.stderr)
                    .durationMillis(durationMillis)
                    .success(execResult.exitCode == 0)
                    .peakMemoryBytes(peakMemoryBytes)
                    .optimizationTip(optimizationTip)
                    .outputFiles(outputFiles)
                    .build();

        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            long durationMillis = endTime - startTime;

            String errorMsg = String.format(
                    "Failed to execute in container for requestId=%s, functionId=%s, runtime=%s",
                    requestId, functionId, runtime
            );
            log.error(errorMsg, e);

            // 실패한 경우에도 ExecutionResult 반환
            return ExecutionResult.builder()
                    .requestId(requestId)
                    .functionId(functionId)
                    .exitCode(-1)
                    .stdout("")
                    .stderr("Execution failed: " + e.getMessage())
                    .durationMillis(durationMillis)
                    .success(false)
                    .peakMemoryBytes(null)
                    .optimizationTip(null)
                    .outputFiles(List.of())
                    .build();

        } finally {
            // 7. 컨테이너를 Warm Pool에 반환
            if (containerId != null) {
                try {
                    warmPoolManager.releaseContainer(runtimeType, containerId);
                    log.debug("Released container: {} back to Warm Pool", containerId);
                } catch (Exception ex) {
                    log.error("Failed to release container: {}", containerId, ex);
                }
            }
        }
    }

    /**
     * TaskMessage의 runtime을 WarmPoolManager.RuntimeType으로 변환
     */
    private WarmPoolManager.RuntimeType resolveRuntimeType(TaskMessage taskMessage) {
        String runtime = taskMessage.getRuntime();
        if (runtime == null) {
            throw new IllegalArgumentException("Runtime is null");
        }

        return switch (runtime.toLowerCase()) {
            case "python" -> WarmPoolManager.RuntimeType.PYTHON;
            case "cpp", "c++" -> WarmPoolManager.RuntimeType.CPP;
            default -> throw new IllegalArgumentException("Unsupported runtime: " + runtime);
        };
    }

    /**
     * 런타임별 실행 커맨드 구성
     */
    private List<String> buildCommandForRuntime(TaskMessage taskMessage, String containerWorkDir) {
        String runtime = taskMessage.getRuntime().toLowerCase();

        return switch (runtime) {
            case "python" -> List.of("python", "main.py");
            case "cpp", "c++" -> List.of("/bin/bash", "run.sh");
            default -> throw new IllegalArgumentException("Unsupported runtime: " + runtime);
        };
    }

    /**
     * 컨테이너 내부에서 명령 실행 (docker exec)
     */
    private ExecResult executeInContainer(String containerId, String workDir, List<String> cmd) {
        try {
            // Exec 생성
            ExecCreateCmdResponse execCreateResponse = dockerClient.execCreateCmd(containerId)
                    .withCmd(cmd.toArray(new String[0]))
                    .withWorkingDir(workDir)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            String execId = execCreateResponse.getId();
            log.debug("Created exec: {} in container: {}", execId, containerId);

            // Stdout/Stderr 수집용
            StringBuilder stdoutBuilder = new StringBuilder();
            StringBuilder stderrBuilder = new StringBuilder();

            // Exec 실행 및 로그 수집
            ExecStartResultCallback callback = new ExecStartResultCallback() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                    String log = new String(frame.getPayload()).trim();

                    switch (frame.getStreamType()) {
                        case STDOUT, RAW -> stdoutBuilder.append(log).append("\n");
                        case STDERR -> stderrBuilder.append(log).append("\n");
                    }
                }
            };

            dockerClient.execStartCmd(execId)
                    .exec(callback)
                    .awaitCompletion(60, TimeUnit.SECONDS);

            // Exit code 가져오기
            Integer exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong().intValue();

            log.debug("Exec {} finished with exit code: {}", execId, exitCode);

            return new ExecResult(
                    exitCode != null ? exitCode : -1,
                    stdoutBuilder.toString(),
                    stderrBuilder.toString()
            );

        } catch (InterruptedException e) {
            log.error("Exec execution interrupted in container: {}", containerId, e);
            Thread.currentThread().interrupt();
            return new ExecResult(-1, "", "Execution interrupted");
        } catch (Exception e) {
            log.error("Failed to execute in container: {}", containerId, e);
            return new ExecResult(-1, "", "Execution failed: " + e.getMessage());
        }
    }

    /**
     * Exec 실행 결과를 담는 내부 레코드
     */
    private record ExecResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * Output 디렉터리 생성 (호스트 측)
     *
     * @param requestId 요청 ID
     * @return 생성된 디렉터리 절대 경로
     */
    private String createOutputDirectory(String requestId) {
        try {
            String outputBasePath = agentProperties.getOutput().getBaseDir();
            Path outputDir = Paths.get(outputBasePath, requestId);

            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
                log.debug("Created output directory: {}", outputDir);
            }

            return outputDir.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("Failed to create output directory for requestId: {}", requestId, e);
            throw new RuntimeException("Failed to create output directory", e);
        }
    }
}

