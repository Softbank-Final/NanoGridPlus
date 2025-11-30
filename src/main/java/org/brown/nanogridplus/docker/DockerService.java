package org.brown.nanogridplus.docker;

import org.brown.nanogridplus.model.ExecutionResult;
import org.brown.nanogridplus.model.TaskMessage;

import java.nio.file.Path;

/**
 * Docker 컨테이너 실행 서비스 인터페이스
 */
public interface DockerService {

    /**
     * 주어진 TaskMessage와 작업 디렉터리(workDir)를 기반으로
     * 적절한 런타임 컨테이너를 생성/실행하고, 실행 결과(로그, exitCode 등)를 반환한다.
     *
     * 이 단계에서는 매 요청마다 새 컨테이너를 만들고, 실행 후 종료/삭제한다.
     * Warm Pool, Pause/Unpause, Auto-Tuner는 이후 단계에서 붙일 예정이다.
     *
     * @param taskMessage SQS로부터 받은 작업 메시지
     * @param workDir 작업 코드가 있는 호스트 디렉터리
     * @return 실행 결과 (exitCode, stdout, stderr, durationMillis 등)
     * @throws RuntimeException 컨테이너 실행 실패 시
     */
    ExecutionResult runTask(TaskMessage taskMessage, Path workDir);
}

