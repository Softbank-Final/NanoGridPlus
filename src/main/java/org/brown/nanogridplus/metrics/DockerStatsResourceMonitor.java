package org.brown.nanogridplus.metrics;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Docker Stats를 사용한 리소스 모니터 구현
 * 
 * docker stats 명령을 통해 컨테이너의 메모리 사용량을 측정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerStatsResourceMonitor implements ResourceMonitor {

    private final DockerClient dockerClient;

    @Override
    public Long measurePeakMemoryBytes(String containerId) {
        log.debug("Measuring peak memory for container: {}", containerId);

        AtomicLong memoryUsage = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);

        try {
            dockerClient.statsCmd(containerId)
                    .exec(new ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics stats) {
                            try {
                                if (stats != null && stats.getMemoryStats() != null) {
                                    Long usage = stats.getMemoryStats().getUsage();
                                    if (usage != null) {
                                        memoryUsage.set(usage);
                                        log.debug("Memory usage for container {}: {} bytes", containerId, usage);
                                    }
                                }
                            } finally {
                                // 한 번만 읽고 종료
                                latch.countDown();
                                try {
                                    close();
                                } catch (Exception e) {
                                    log.debug("Error closing stats callback", e);
                                }
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            log.warn("Error reading stats for container: {}", containerId, throwable);
                            latch.countDown();
                        }
                    });

            // 최대 5초 대기
            boolean success = latch.await(5, TimeUnit.SECONDS);
            if (!success) {
                log.warn("Timeout waiting for stats for container: {}", containerId);
                return null;
            }

            long usage = memoryUsage.get();
            if (usage == 0) {
                log.warn("No memory stats available for container: {}", containerId);
                return null;
            }

            log.info("Measured peak memory for container {}: {} bytes ({} MB)",
                    containerId, usage, usage / 1024 / 1024);
            return usage;

        } catch (Exception e) {
            log.error("Failed to measure memory for container: {}", containerId, e);
            return null;
        }
    }
}

