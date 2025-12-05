package org.brown.nanogridplus.s3;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Output Binding - 컨테이너 실행 후 생성된 파일을 S3에 자동 업로드
 *
 * 사용자 코드가 /workspace-root/{requestId}/output 디렉터리에 파일을 생성하면
 * 이 서비스가 자동으로 S3에 업로드하고 URL 리스트를 반환합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutputFileUploader {

    private final S3Client s3Client;
    private final DockerClient dockerClient;
    private final AgentProperties agentProperties;

    /**
     * 컨테이너 내부의 output 디렉터리에서 파일을 복사하여 S3에 업로드
     *
     * @param requestId   요청 ID
     * @param containerId 컨테이너 ID
     * @return 업로드된 파일의 S3 URL 리스트
     */
    public List<String> uploadOutputFiles(String requestId, String containerId) {
        if (!agentProperties.getOutput().isEnabled()) {
            log.debug("Output file upload is disabled");
            return List.of();
        }

        // 1. 컨테이너 내부 output 경로 확인
        String containerOutputPath = String.format("%s/%s/output",
                agentProperties.getDocker().getWorkDirRoot(),
                requestId);

        log.info("📤 [OUTPUT] Checking container output directory: {}", containerOutputPath);

        // 2. 컨테이너 내부에 output 디렉터리가 있는지 확인
        boolean hasOutput = checkOutputDirectoryExists(containerId, containerOutputPath);
        if (!hasOutput) {
            log.debug("No output directory found in container");
            return List.of();
        }

        // 3. 호스트의 임시 디렉터리 생성
        String outputHostPath = agentProperties.getOutput().getBaseDir() + "/" + requestId;
        Path outputDir = Paths.get(outputHostPath);

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            log.error("Failed to create output directory: {}", outputHostPath, e);
            return List.of();
        }

        // 4. 컨테이너에서 파일 복사
        copyOutputFilesFromContainer(containerId, containerOutputPath, outputHostPath);

        // 5. 호스트에서 S3로 업로드
        List<String> uploadedUrls = uploadToS3(requestId, outputDir);

        // 6. 정리
        cleanupOutputDirectory(outputDir);

        return uploadedUrls;
    }

    /**
     * 컨테이너 내부에 output 디렉터리가 존재하는지 확인
     */
    private boolean checkOutputDirectoryExists(String containerId, String path) {
        try {
            var execCreate = dockerClient.execCreateCmd(containerId)
                    .withCmd("test", "-d", path)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            var callback = new ExecStartResultCallback();
            dockerClient.execStartCmd(execCreate.getId())
                    .exec(callback)
                    .awaitCompletion(5, TimeUnit.SECONDS);

            var inspect = dockerClient.inspectExecCmd(execCreate.getId()).exec();
            return inspect.getExitCodeLong() == 0;

        } catch (Exception e) {
            log.debug("Failed to check output directory: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 컨테이너에서 호스트로 파일 복사
     */
    private void copyOutputFilesFromContainer(String containerId, String containerPath, String hostPath) {
        try {
            // 컨테이너 내부 파일 목록 가져오기
            var listExec = dockerClient.execCreateCmd(containerId)
                    .withCmd("find", containerPath, "-type", "f")
                    .withAttachStdout(true)
                    .exec();

            StringBuilder fileListBuilder = new StringBuilder();
            var listCallback = new ExecStartResultCallback() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame frame) {
                    fileListBuilder.append(new String(frame.getPayload()));
                }
            };

            dockerClient.execStartCmd(listExec.getId())
                    .exec(listCallback)
                    .awaitCompletion(10, TimeUnit.SECONDS);

            String[] files = fileListBuilder.toString().trim().split("\n");

            for (String containerFilePath : files) {
                if (containerFilePath.isEmpty()) continue;

                // 파일 이름 추출
                String fileName = Paths.get(containerFilePath).getFileName().toString();
                Path hostFilePath = Paths.get(hostPath, fileName);

                // 파일 복사
                copyFileFromContainer(containerId, containerFilePath, hostFilePath);

                log.debug("Copied file from container: {} -> {}", containerFilePath, hostFilePath);
            }

        } catch (Exception e) {
            log.warn("Failed to copy output files from container: {}", containerId, e);
        }
    }

    /**
     * 컨테이너에서 단일 파일 복사
     */
    private void copyFileFromContainer(String containerId, String containerFilePath, Path hostFilePath) {
        try {
            var execCreate = dockerClient.execCreateCmd(containerId)
                    .withCmd("cat", containerFilePath)
                    .withAttachStdout(true)
                    .exec();

            try (FileOutputStream fos = new FileOutputStream(hostFilePath.toFile())) {
                var callback = new ExecStartResultCallback() {
                    @Override
                    public void onNext(com.github.dockerjava.api.model.Frame frame) {
                        try {
                            fos.write(frame.getPayload());
                        } catch (IOException e) {
                            log.error("Failed to write file: {}", hostFilePath, e);
                        }
                    }
                };

                dockerClient.execStartCmd(execCreate.getId())
                        .exec(callback)
                        .awaitCompletion(30, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            log.error("Failed to copy file from container: {}", containerFilePath, e);
        }
    }

    /**
     * 호스트 디렉터리의 파일들을 S3에 업로드
     */
    private List<String> uploadToS3(String requestId, Path outputDir) {
        List<String> uploadedUrls = new ArrayList<>();
        String bucket = agentProperties.getS3().getUserDataBucket();
        String s3Prefix = agentProperties.getOutput().getS3Prefix();

        try (Stream<Path> paths = Files.walk(outputDir)) {
            List<File> files = paths
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .toList();

            if (files.isEmpty()) {
                log.info("No output files found in directory: {}", outputDir);
                return List.of();
            }

            log.info("Found {} output file(s) to upload", files.size());

            for (File file : files) {
                try {
                    String fileName = file.getName();
                    String s3Key = String.format("%s/%s/%s", s3Prefix, requestId, fileName);

                    log.debug("Uploading file: {} -> s3://{}/{}", file.getAbsolutePath(), bucket, s3Key);

                    // S3 업로드
                    PutObjectRequest putRequest = PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(s3Key)
                            .contentType(guessContentType(fileName))
                            .build();

                    s3Client.putObject(putRequest, RequestBody.fromFile(file));

                    // URL 생성 (Public URL 형식)
                    String url = String.format("https://%s.s3.%s.amazonaws.com/%s",
                            bucket,
                            agentProperties.getAws().getRegion(),
                            s3Key);

                    uploadedUrls.add(url);
                    log.info("✅ [OUTPUT] Uploaded: {} -> {}", fileName, url);

                } catch (Exception e) {
                    log.error("Failed to upload file: {}", file.getName(), e);
                }
            }

        } catch (IOException e) {
            log.error("Failed to scan output directory: {}", outputDir, e);
        }

        log.info("📦 [OUTPUT] Total uploaded: {} file(s) for requestId={}", uploadedUrls.size(), requestId);
        return uploadedUrls;
    }

    /**
     * Output 디렉터리 정리 (파일 삭제)
     */
    private void cleanupOutputDirectory(Path outputDir) {
        try {
            try (Stream<Path> paths = Files.walk(outputDir)) {
                paths.sorted((p1, p2) -> -p1.compareTo(p2)) // 역순으로 정렬 (파일 먼저, 디렉터리 나중)
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", path, e);
                            }
                        });
            }
            log.debug("Cleaned up output directory: {}", outputDir);
        } catch (IOException e) {
            log.warn("Failed to cleanup output directory: {}", outputDir, e);
        }
    }

    /**
     * 파일 확장자로 Content-Type 추측
     */
    private String guessContentType(String fileName) {
        String lower = fileName.toLowerCase();
        
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return "application/gzip";
        
        return "application/octet-stream";  // 기본값
    }
}

