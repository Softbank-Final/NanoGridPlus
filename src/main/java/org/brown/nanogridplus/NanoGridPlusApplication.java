package org.brown.nanogridplus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NanoGrid Plus - Smart Worker Agent (NanoAgent)
 *
 * Project NanoGrid Plus: Intelligent Hybrid FaaS
 * Data Plane (C) - EC2 기반 Smart Worker Agent
 *
 * 주요 기능:
 * - SQS Long Polling을 통한 작업 수신
 * - S3에서 코드 다운로드 (향후 구현)
 * - Docker Warm Pool을 이용한 함수 실행 (향후 구현)
 * - Auto-Tuner를 통한 메모리 최적화 (향후 구현)
 * - Redis를 통한 메트릭 전송 (향후 구현)
 *
 * @author NanoGrid Plus Team
 * @version 0.1 (0~1단계: SQS 수신 구조 및 프로젝트 뼈대)
 */
@SpringBootApplication
public class NanoGridPlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NanoGridPlusApplication.class, args);
    }

}
