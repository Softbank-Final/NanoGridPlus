package org.brown.nanogridplus.metrics;

/**
 * 리소스 모니터링 인터페이스
 *
 * Docker 컨테이너의 리소스 사용량을 측정한다.
 */
public interface ResourceMonitor {

    /**
     * 주어진 컨테이너 ID에 대해 메모리 사용량(또는 피크 메모리)을 측정한다.
     * 측정에 실패하면 null을 반환할 수 있다.
     *
     * @param containerId 컨테이너 ID
     * @return 피크 메모리 사용량 (바이트 단위), 측정 실패 시 null
     */
    Long measurePeakMemoryBytes(String containerId);
}

