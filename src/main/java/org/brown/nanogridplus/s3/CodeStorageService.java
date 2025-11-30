package org.brown.nanogridplus.s3;

import org.brown.nanogridplus.model.TaskMessage;

import java.nio.file.Path;

/**
 * 코드 저장소 서비스 인터페이스
 * S3에서 코드를 다운로드하고 작업 디렉터리를 준비하는 역할
 */
public interface CodeStorageService {

    /**
     * 주어진 TaskMessage에 해당하는 코드 zip을 S3에서 다운로드하여
     * 작업 디렉터리에 압축 해제하고, 해당 작업 디렉터리의 Path를 반환한다.
     *
     * @param taskMessage SQS로부터 받은 작업 메시지
     * @return 작업 디렉터리 Path (예: /tmp/task/{requestId})
     * @throws RuntimeException S3 다운로드 실패 또는 압축 해제 실패 시
     */
    Path prepareWorkingDirectory(TaskMessage taskMessage);
}

