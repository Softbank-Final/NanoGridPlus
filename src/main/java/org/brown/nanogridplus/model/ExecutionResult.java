package org.brown.nanogridplus.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Docker 컨테이너 실행 결과를 담는 DTO
 *
 * 향후 확장 가능 필드:
 * - peakMemoryBytes (Auto-Tuner)
 * - cpuUsagePercent (Auto-Tuner)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResult {

    /**
     * 요청 고유 ID
     */
    private String requestId;

    /**
     * 함수 ID
     */
    private String functionId;

    /**
     * 컨테이너 종료 코드 (0 = 성공)
     */
    private int exitCode;

    /**
     * 표준 출력 (stdout)
     */
    private String stdout;

    /**
     * 표준 에러 (stderr)
     */
    private String stderr;

    /**
     * 실행 소요 시간 (밀리초)
     */
    private long durationMillis;

    /**
     * 실행 성공 여부 (exitCode == 0)
     */
    private boolean success;

    /**
     * 피크 메모리 사용량 (바이트 단위) - Auto-Tuner
     */
    private Long peakMemoryBytes;

    /**
     * 메모리 최적화 팁 - Auto-Tuner
     */
    private String optimizationTip;

    /**
     * Output Binding으로 업로드된 파일 URL 리스트
     */
    private java.util.List<String> outputFiles;

    @Override
    public String toString() {
        return String.format(
                "ExecutionResult[requestId=%s, functionId=%s, exitCode=%d, durationMillis=%d, success=%s, peakMemoryBytes=%s]",
                requestId, functionId, exitCode, durationMillis, success, peakMemoryBytes
        );
    }
}

