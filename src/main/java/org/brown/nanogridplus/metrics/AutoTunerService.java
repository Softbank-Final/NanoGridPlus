package org.brown.nanogridplus.metrics;

import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.model.TaskMessage;
import org.springframework.stereotype.Service;

/**
 * Auto-Tuner 서비스
 *
 * 실제 메모리 사용량과 할당된 메모리를 비교하여
 * 최적화 팁을 생성한다.
 */
@Slf4j
@Service
public class AutoTunerService {

    private static final int DEFAULT_MEMORY_MB = 128;

    /**
     * 메모리 최적화 팁 생성
     *
     * @param taskMessage 작업 메시지 (할당 메모리 정보 포함)
     * @param peakMemoryBytes 측정된 피크 메모리 사용량 (바이트)
     * @return 최적화 팁 문자열, 정보가 부족하면 null
     */
    public String createOptimizationTip(TaskMessage taskMessage, Long peakMemoryBytes) {
        if (peakMemoryBytes == null) {
            log.debug("Peak memory is null, cannot create optimization tip");
            return "메모리 사용량 정보를 가져올 수 없습니다.";
        }

        // 할당 메모리 결정 (메시지에 있으면 사용, 없으면 기본값)
        int allocatedMb = (taskMessage.getMemoryMb() != null)
                ? taskMessage.getMemoryMb()
                : DEFAULT_MEMORY_MB;

        long allocatedBytes = allocatedMb * 1024L * 1024L;
        double ratio = (double) peakMemoryBytes / (double) allocatedBytes;

        log.info("Auto-Tuner analysis: functionId={}, allocatedMb={}, peakMemoryBytes={}, ratio={}",
                taskMessage.getFunctionId(), allocatedMb, peakMemoryBytes, String.format("%.2f", ratio));

        String tip = generateTipByRatio(allocatedMb, peakMemoryBytes, ratio);
        log.info("Generated optimization tip: {}", tip);

        return tip;
    }

    /**
     * 메모리 사용 비율에 따른 팁 생성
     */
    private String generateTipByRatio(int allocatedMb, long peakMemoryBytes, double ratio) {
        long peakMemoryMb = peakMemoryBytes / 1024 / 1024;

        if (ratio < 0.3) {
            // 사용량이 매우 낮음 (30% 미만)
            int recommendedMb = (int) Math.ceil(peakMemoryMb * 1.5);
            return String.format(
                    "💡 Tip: 현재 메모리 설정(%dMB)에 비해 실제 사용량(%dMB)이 매우 낮습니다. " +
                            "메모리를 %dMB 정도로 줄이면 비용을 약 %.0f%% 절감할 수 있습니다.",
                    allocatedMb, peakMemoryMb, recommendedMb,
                    (1.0 - (double) recommendedMb / allocatedMb) * 100
            );

        } else if (ratio < 0.7) {
            // 사용량이 적당히 여유 있음 (30~70%)
            int recommendedMb = (int) Math.ceil(peakMemoryMb * 1.3);
            return String.format(
                    "✅ Tip: 현재 메모리 설정(%dMB)이 비교적 여유 있습니다(사용량: %dMB). " +
                            "더 절감하려면 %dMB로 조정할 수 있습니다.",
                    allocatedMb, peakMemoryMb, recommendedMb
            );

        } else if (ratio <= 1.0) {
            // 사용량이 적절함 (70~100%)
            return String.format(
                    "✅ Tip: 현재 메모리 설정(%dMB)이 적절합니다. " +
                            "피크 사용량(%dMB)이 설정 범위 내에 있습니다.",
                    allocatedMb, peakMemoryMb
            );

        } else {
            // 사용량이 초과함 (100% 초과)
            int recommendedMb = (int) Math.ceil(peakMemoryMb * 1.2);
            return String.format(
                    "⚠️ Tip: 피크 메모리 사용량(%dMB)이 현재 설정(%dMB)을 초과했습니다. " +
                            "안정적인 실행을 위해 메모리를 %dMB 이상으로 늘리는 것을 권장합니다.",
                    peakMemoryMb, allocatedMb, recommendedMb
            );
        }
    }
}

