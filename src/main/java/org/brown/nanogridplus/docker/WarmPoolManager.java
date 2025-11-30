package org.brown.nanogridplus.docker;

/**
 * Warm Pool 관리 인터페이스
 *
 * 미리 생성한 컨테이너를 Pause 상태로 유지하다가
 * 요청 시 Unpause하여 재사용함으로써 Cold Start를 제거한다.
 */
public interface WarmPoolManager {

    /**
     * 런타임 타입 열거형
     */
    enum RuntimeType {
        PYTHON,
        CPP
    }

    /**
     * 지정한 런타임에 대해 사용 가능한 컨테이너 하나를 풀에서 가져온다.
     * 풀에 사용 가능한 컨테이너가 없으면 새로 생성한다.
     *
     * @param runtimeType 런타임 타입 (PYTHON, CPP)
     * @return 컨테이너 ID (unpause 완료 상태)
     */
    String acquireContainer(RuntimeType runtimeType);

    /**
     * 작업이 끝난 컨테이너를 다시 풀에 되돌린다.
     * 재사용이 불가능하다고 판단되면 stop/remove 하고 풀에서 제거한다.
     *
     * @param runtimeType 런타임 타입
     * @param containerId 컨테이너 ID
     */
    void releaseContainer(RuntimeType runtimeType, String containerId);
}

