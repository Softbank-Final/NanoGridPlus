package org.brown.nanogridplus.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Docker Warm Pool Manager 구현
 *
 * 애플리케이션 시작 시 런타임별로 컨테이너를 미리 생성하고
 * Pause 상태로 유지하다가 요청 시 Unpause하여 재사용한다.
 *
 * 현재 단계 (4단계):
 * - Python, C++ 런타임별 컨테이너 Pool 관리
 * - acquireContainer: Pool에서 꺼내서 Unpause
 * - releaseContainer: 작업 완료 후 Pause하고 Pool에 반환
 *
 * 향후 확장:
 * - Auto-Tuner 연동 (5단계)
 * - Pool 크기 동적 조정
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.warmPool.enabled", havingValue = "true", matchIfMissing = true)
public class DockerWarmPoolManager implements WarmPoolManager {

    private final DockerClient dockerClient;
    private final AgentProperties agentProperties;

    // 런타임별 컨테이너 ID Pool (동시성 안전)
    private final Map<RuntimeType, ConcurrentLinkedDeque<String>> pool = new ConcurrentHashMap<>();

    /**
     * 애플리케이션 시작 시 Warm Pool 초기화
     */
    @PostConstruct
    public void initialize() {
        log.info("========================================");
        log.info("Initializing Warm Pool Manager");
        log.info("========================================");

        // Python Pool 초기화
        int pythonSize = agentConfig.getWarmPool().getPythonSize();
        log.info("Creating {} Python containers for Warm Pool", pythonSize);
        ConcurrentLinkedDeque<String> pythonPool = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < pythonSize; i++) {
            String containerId = createAndPauseContainer(RuntimeType.PYTHON);
            pythonPool.add(containerId);
            log.info("  [{}] Python container created: {}", i + 1, containerId);
        }
        pool.put(RuntimeType.PYTHON, pythonPool);

        // C++ Pool 초기화
        int cppSize = agentConfig.getWarmPool().getCppSize();
        log.info("Creating {} C++ containers for Warm Pool", cppSize);
        ConcurrentLinkedDeque<String> cppPool = new ConcurrentLinkedDeque<>();
        for (int i = 0; i < cppSize; i++) {
            String containerId = createAndPauseContainer(RuntimeType.CPP);
            cppPool.add(containerId);
            log.info("  [{}] C++ container created: {}", i + 1, containerId);
        }
        pool.put(RuntimeType.CPP, cppPool);

        log.info("Warm Pool initialization completed");
        log.info("  - Python Pool: {} containers", pythonPool.size());
        log.info("  - C++ Pool: {} containers", cppPool.size());
        log.info("========================================");
    }

    /**
     * 컨테이너 생성 및 Pause
     */
    private String createAndPauseContainer(RuntimeType runtimeType) {
        String imageName = getImageName(runtimeType);
        String containerName = "nanogrid-warmpool-" + runtimeType.name().toLowerCase() + "-" + System.currentTimeMillis();

        log.debug("Creating warm pool container: {} with image: {}", containerName, imageName);

        // 볼륨 마운트: /tmp/task → /workspace-root
        String hostPath = agentProperties.getTaskBaseDir();
        String containerPath = agentProperties.getDocker().getWorkDirRoot();
        Volume volume = new Volume(containerPath);
        Bind bind = new Bind(hostPath, volume);

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(bind);

        // 컨테이너 생성 (sleep으로 유지)
        CreateContainerResponse container = dockerClient.createContainerCmd(imageName)
                .withName(containerName)
                .withCmd("sleep", "infinity")
                .withHostConfig(hostConfig)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        String containerId = container.getId();

        // 컨테이너 시작
        dockerClient.startContainerCmd(containerId).exec();
        log.debug("Started container: {}", containerId);

        // 컨테이너 Pause
        dockerClient.pauseContainerCmd(containerId).exec();
        log.debug("Paused container: {}", containerId);

        return containerId;
    }

    /**
     * Pool에서 컨테이너 획득 (Unpause 포함)
     */
    @Override
    public String acquireContainer(RuntimeType runtimeType) {
        log.debug("Acquiring container for runtime: {}", runtimeType);

        ConcurrentLinkedDeque<String> runtimePool = pool.get(runtimeType);
        if (runtimePool == null) {
            throw new IllegalStateException("Pool not initialized for runtime: " + runtimeType);
        }

        // Pool에서 컨테이너 가져오기
        String containerId = runtimePool.poll();

        // Pool이 비어있으면 새로 생성
        if (containerId == null) {
            log.warn("Pool is empty for runtime: {}, creating new container", runtimeType);
            containerId = createAndPauseContainer(runtimeType);
        }

        // Unpause
        try {
            dockerClient.unpauseContainerCmd(containerId).exec();
            log.info("Acquired and unpaused container: {} for runtime: {}", containerId, runtimeType);
            return containerId;
        } catch (Exception e) {
            log.error("Failed to unpause container: {}, creating new one", containerId, e);
            // 실패한 컨테이너 정리
            cleanupContainer(containerId);
            // 새로 생성해서 반환
            containerId = createAndPauseContainer(runtimeType);
            dockerClient.unpauseContainerCmd(containerId).exec();
            return containerId;
        }
    }

    /**
     * 컨테이너를 Pool에 반환 (Pause 포함)
     */
    @Override
    public void releaseContainer(RuntimeType runtimeType, String containerId) {
        log.debug("Releasing container: {} for runtime: {}", containerId, runtimeType);

        try {
            // 컨테이너 상태 확인
            InspectContainerResponse inspection = dockerClient.inspectContainerCmd(containerId).exec();
            Boolean running = inspection.getState().getRunning();

            if (running == null || !running) {
                log.warn("Container {} is not running, removing from pool", containerId);
                cleanupContainer(containerId);
                return;
            }

            // Pause 상태로 전환
            dockerClient.pauseContainerCmd(containerId).exec();
            log.debug("Paused container: {}", containerId);

            // Pool에 반환
            ConcurrentLinkedDeque<String> runtimePool = pool.get(runtimeType);
            if (runtimePool != null) {
                runtimePool.offer(containerId);
                log.info("Released container: {} back to {} pool (current size: {})",
                        containerId, runtimeType, runtimePool.size());
            }

        } catch (Exception e) {
            log.error("Failed to release container: {}, removing from pool", containerId, e);
            cleanupContainer(containerId);
        }
    }

    /**
     * 컨테이너 정리 (Stop & Remove)
     */
    private void cleanupContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(5)
                    .exec();
            log.debug("Stopped container: {}", containerId);
        } catch (Exception e) {
            log.debug("Container already stopped: {}", containerId);
        }

        try {
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();
            log.debug("Removed container: {}", containerId);
        } catch (Exception e) {
            log.warn("Failed to remove container: {}", containerId, e);
        }
    }

    /**
     * 런타임 타입에 따른 이미지 이름 반환
     */
    private String getImageName(RuntimeType runtimeType) {
        return switch (runtimeType) {
            case PYTHON -> agentProperties.getDocker().getPythonImage();
            case CPP -> agentProperties.getDocker().getCppImage();
        };
    }

    /**
     * 애플리케이션 종료 시 Pool 정리
     */
    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up Warm Pool containers...");

        for (Map.Entry<RuntimeType, ConcurrentLinkedDeque<String>> entry : pool.entrySet()) {
            RuntimeType runtimeType = entry.getKey();
            ConcurrentLinkedDeque<String> runtimePool = entry.getValue();

            log.info("Cleaning up {} pool ({} containers)", runtimeType, runtimePool.size());

            while (!runtimePool.isEmpty()) {
                String containerId = runtimePool.poll();
                if (containerId != null) {
                    cleanupContainer(containerId);
                }
            }
        }

        log.info("Warm Pool cleanup completed");
    }
}

