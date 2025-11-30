package org.brown.nanogridplus.metrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Instant;

/**
 * CloudWatch 커스텀 메트릭 퍼블리셔
 *
 * Auto-Tuner가 측정한 메트릭을 AWS CloudWatch로 전송한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudWatchMetricsPublisher {

    private final CloudWatchClient cloudWatchClient;

    private static final String NAMESPACE = "NanoGrid/FunctionRunner";
    private static final String METRIC_NAME_PEAK_MEMORY = "PeakMemoryBytes";

    /**
     * 피크 메모리 사용량을 CloudWatch에 전송
     *
     * @param functionId 함수 ID
     * @param runtime 런타임 (python, cpp 등)
     * @param peakMemoryBytes 피크 메모리 사용량 (바이트)
     */
    public void publishPeakMemory(String functionId, String runtime, Long peakMemoryBytes) {
        if (peakMemoryBytes == null) {
            log.debug("Peak memory is null, skipping CloudWatch publish");
            return;
        }

        try {
            log.info("Publishing peak memory metric to CloudWatch: functionId={}, runtime={}, bytes={}",
                    functionId, runtime, peakMemoryBytes);

            Dimension functionIdDimension = Dimension.builder()
                    .name("FunctionId")
                    .value(functionId)
                    .build();

            Dimension runtimeDimension = Dimension.builder()
                    .name("Runtime")
                    .value(runtime)
                    .build();

            MetricDatum datum = MetricDatum.builder()
                    .metricName(METRIC_NAME_PEAK_MEMORY)
                    .unit(StandardUnit.BYTES)
                    .value(peakMemoryBytes.doubleValue())
                    .timestamp(Instant.now())
                    .dimensions(functionIdDimension, runtimeDimension)
                    .build();

            PutMetricDataRequest request = PutMetricDataRequest.builder()
                    .namespace(NAMESPACE)
                    .metricData(datum)
                    .build();

            cloudWatchClient.putMetricData(request);

            log.info("Successfully published peak memory metric to CloudWatch");

        } catch (Exception e) {
            // CloudWatch 전송 실패는 메인 로직에 영향을 주지 않도록 예외를 삼킨다
            log.warn("Failed to publish metric to CloudWatch for functionId={}, runtime={}",
                    functionId, runtime, e);
        }
    }
}

