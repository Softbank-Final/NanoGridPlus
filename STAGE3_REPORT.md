# NanoGrid Plus - 3단계 구현 완료 보고서

## ✅ 3단계: Docker Orchestrator (컨테이너 실행) 구현 완료

### 구현 일자
2025-11-30

### 구현 범위
**Docker 컨테이너를 사용하여 작업 코드를 실제로 실행하고 결과를 수집하는 기능 완성**

---

## 📋 구현 내역

### 1. docker-java 의존성 추가 ✅

**파일**: `build.gradle`

**추가된 의존성**:
```gradle
implementation 'com.github.docker-java:docker-java-core:3.3.4'
implementation 'com.github.docker-java:docker-java-transport-httpclient5:3.3.4'
```

---

### 2. ExecutionResult DTO 생성 ✅

**파일**: `org.brown.nanogridplus.model.ExecutionResult`

**필드**:
```java
private String requestId;
private String functionId;
private int exitCode;
private String stdout;
private String stderr;
private long durationMillis;
private boolean success;
```

**특징**:
- Lombok `@Data`, `@Builder` 사용
- 빌더 패턴 지원
- 커스텀 `toString()` (핵심 정보 출력)
- 향후 확장 가능 (peakMemoryBytes, cpuUsagePercent 등)

---

### 3. AgentConfig에 Docker 설정 추가 ✅

**파일**: `org.brown.nanogridplus.config.AgentConfig`

**추가된 내부 클래스**:
```java
@Data
public static class DockerConfig {
    private String pythonImage = "python:3.9-slim";
    private String cppImage = "gcc:11";
    private String workDirInContainer = "/workspace";
    private long defaultTimeoutMillis = 10000;
}
```

**application.yml 설정**:
```yaml
agent:
  docker:
    pythonImage: python:3.9-slim
    cppImage: gcc:11
    workDirInContainer: /workspace
    defaultTimeoutMillis: 10000
```

---

### 4. DockerConfig 클래스 생성 (DockerClient Bean) ✅

**파일**: `org.brown.nanogridplus.docker.DockerConfig`

**주요 내용**:
```java
@Bean
public DockerClient dockerClient() {
    DefaultDockerClientConfig config = DefaultDockerClientConfig
        .createDefaultConfigBuilder()
        .build();

    ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .maxConnections(100)
        .connectionTimeout(Duration.ofSeconds(30))
        .responseTimeout(Duration.ofSeconds(45))
        .build();

    return DockerClientImpl.getInstance(config, httpClient);
}
```

**특징**:
- 기본 Docker 소켓 (`/var/run/docker.sock`) 연결
- Connection Pool 설정 (최대 100개)
- 타임아웃 설정 (연결: 30초, 응답: 45초)

---

### 5. DockerService 인터페이스 생성 ✅

**파일**: `org.brown.nanogridplus.docker.DockerService`

**메서드 시그니처**:
```java
public interface DockerService {
    ExecutionResult runTask(TaskMessage taskMessage, Path workDir);
}
```

---

### 6. DockerEngineService 구현 클래스 생성 ✅

**파일**: `org.brown.nanogridplus.docker.DockerEngineService`

#### 주요 기능

##### 6.1) 이미지 선택 (selectImage)
```java
private String selectImage(String runtime)
```
- `python` → `pythonImage` (python:3.9-slim)
- `cpp`, `c++` → `cppImage` (gcc:11)
- 지원하지 않는 런타임 → `IllegalArgumentException`

##### 6.2) 컨테이너 생성 (createContainer)
```java
private String createContainer(TaskMessage taskMessage, Path workDir, String imageName)
```

**주요 기능**:
- 컨테이너 이름: `nanogrid-{requestId}`
- 볼륨 마운트: `호스트 workDir → /workspace`
- 작업 디렉터리: `/workspace`
- 런타임별 명령:
  - `python`: `["python", "main.py"]`
  - `cpp`: `["/bin/bash", "run.sh"]`
- stdout/stderr 캡처 설정

##### 6.3) 컨테이너 실행 및 대기
```java
dockerClient.startContainerCmd(containerId).exec();
int exitCode = dockerClient.waitContainerCmd(containerId)
        .exec(new WaitContainerResultCallback())
        .awaitStatusCode();
```

##### 6.4) 로그 수집 (collectLogs)
```java
private LogResult collectLogs(String containerId)
```

**주요 기능**:
- `LogContainerCmd` 사용
- stdout, stderr 분리 수집
- `LogContainerResultCallback`로 스트림 처리
- 최대 30초 대기

##### 6.5) 컨테이너 정리 (cleanupContainer)
```java
private void cleanupContainer(String containerId)
```

**주요 기능**:
- 컨테이너 중지 (5초 타임아웃)
- 컨테이너 삭제 (force=true)
- finally 블록에서 항상 실행

##### 6.6) 에러 처리
- 실패 시에도 `ExecutionResult` 반환
- exitCode = -1
- stderr에 에러 메시지 포함
- 상세한 로그 출력

##### 6.7) TODO 주석
```java
// TODO: 타임아웃 처리 (ExecutorService + Future)
// TODO: Warm Pool - 기존 컨테이너 재사용
// TODO: Auto-Tuner - cgroups 메모리 제한 설정
```

---

### 7. SqsPoller에 DockerService 연동 ✅

**파일**: `org.brown.nanogridplus.sqs.SqsPoller`

**변경 사항**:

1. **의존성 주입 추가**:
```java
private final DockerService dockerService;
```

2. **processMessage 메서드에 Docker 실행 추가**:
```java
// 3단계: Docker 컨테이너 실행
ExecutionResult result = dockerService.runTask(taskMessage, workDir);
log.info("Execution finished for request {}: exitCode={}, duration={}ms",
        taskMessage.getRequestId(), result.getExitCode(), result.getDurationMillis());
log.debug("Stdout for {}:\n{}", taskMessage.getRequestId(), result.getStdout());
log.debug("Stderr for {}:\n{}", taskMessage.getRequestId(), result.getStderr());

// TODO: 6단계 - Redis에 결과 Publish
// redisPublisher.publishResult(result);
```

---

## 🎯 실행 흐름

### 전체 파이프라인 (0~3단계)

```
1. SQS 메시지 수신 (SqsPoller)
   ↓
2. JSON → TaskMessage 파싱
   ↓
3. S3에서 코드 다운로드 (CodeStorageService)
   - /tmp/task/{requestId}에 압축 해제
   ↓
4. Docker 이미지 선택 (runtime에 따라)
   - python → python:3.9-slim
   - cpp → gcc:11
   ↓
5. Docker 컨테이너 생성
   - 볼륨 마운트: workDir → /workspace
   - 명령 설정: runtime별
   ↓
6. 컨테이너 실행 및 대기
   - startContainer
   - waitContainer (exitCode 수집)
   ↓
7. 로그 수집
   - stdout, stderr 분리
   - LogContainerResultCallback
   ↓
8. 컨테이너 정리
   - stop + remove
   ↓
9. ExecutionResult 생성 및 반환
   - exitCode, stdout, stderr, durationMillis
   ↓
10. 로그 출력
   ↓
11. SQS 메시지 삭제 (처리 완료)
```

---

## 📊 로그 출력 예시

### 성공 시나리오 (Python)
```
INFO  o.b.n.sqs.SqsPoller : ===== 작업 메시지 수신 =====
INFO  o.b.n.sqs.SqsPoller : Received task: TaskMessage[requestId=test-001, ...]
INFO  o.b.n.s3.S3CodeStorageService : Preparing working directory for request: test-001
INFO  o.b.n.s3.S3CodeStorageService : Successfully prepared working directory: /tmp/task/test-001
INFO  o.b.n.sqs.SqsPoller : Prepared working directory for request test-001 at path: /tmp/task/test-001
INFO  o.b.n.docker.DockerEngineService : Starting Docker execution for request: test-001, runtime: python
INFO  o.b.n.docker.DockerEngineService : Selected Docker image: python:3.9-slim for runtime: python
INFO  o.b.n.docker.DockerEngineService : Created container: abc123def456 for request: test-001
INFO  o.b.n.docker.DockerEngineService : Started container: abc123def456
INFO  o.b.n.docker.DockerEngineService : Container abc123def456 finished with exitCode: 0 in 1234ms
INFO  o.b.n.sqs.SqsPoller : Execution finished for request test-001: exitCode=0, duration=1234ms
DEBUG o.b.n.sqs.SqsPoller : Stdout for test-001:
Hello from NanoGrid!
Result: 42
```

### 실패 시나리오
```
ERROR o.b.n.docker.DockerEngineService : Failed to execute container for requestId=test-002, functionId=func-error, runtime=python
INFO  o.b.n.sqs.SqsPoller : Execution finished for request test-002: exitCode=-1, duration=500ms
DEBUG o.b.n.sqs.SqsPoller : Stderr for test-002:
Container execution failed: Image not found
```

---

## 🧪 테스트 방법

### 1. 테스트 Python 코드 준비

**main.py**:
```python
#!/usr/bin/env python3
print("Hello from NanoGrid!")
print("Result: 42")
```

**requirements.txt** (선택):
```
# 필요한 패키지
```

### 2. zip으로 압축
```bash
mkdir -p test-python
cd test-python
echo 'print("Hello from NanoGrid!")' > main.py
echo 'print("Result: 42")' >> main.py
zip ../test-python.zip main.py
cd ..
```

### 3. S3에 업로드
```bash
aws s3 cp test-python.zip s3://nanogrid-code-bucket/test/python-hello.zip
```

### 4. SQS 메시지 전송
```bash
aws sqs send-message \
  --queue-url YOUR_QUEUE_URL \
  --message-body '{
    "requestId": "test-docker-001",
    "functionId": "python-hello",
    "runtime": "python",
    "s3Bucket": "nanogrid-code-bucket",
    "s3Key": "test/python-hello.zip",
    "timeoutMs": 5000
  }'
```

### 5. 로그 확인
애플리케이션 로그에서 다음을 확인:
- Docker 컨테이너 생성 로그
- 실행 완료 로그 (exitCode=0)
- stdout 출력

---

## 🔧 런타임별 실행 방식

### Python 런타임
- **이미지**: `python:3.9-slim`
- **명령**: `["python", "main.py"]`
- **요구사항**: 작업 디렉터리에 `main.py` 파일 필요

### C++ 런타임
- **이미지**: `gcc:11`
- **명령**: `["/bin/bash", "run.sh"]`
- **요구사항**: 작업 디렉터리에 `run.sh` 스크립트 필요

**run.sh 예시**:
```bash
#!/bin/bash
g++ -o program main.cpp
./program
```

---

## 📦 생성된/수정된 파일

### 신규 생성 (5개)
1. ✨ `org.brown.nanogridplus.model.ExecutionResult` (DTO)
2. ✨ `org.brown.nanogridplus.docker.DockerConfig` (Bean 설정)
3. ✨ `org.brown.nanogridplus.docker.DockerService` (인터페이스)
4. ✨ `org.brown.nanogridplus.docker.DockerEngineService` (구현체)
5. ✨ `org.brown.nanogridplus.docker.DockerService.java` (빈 껍데기 삭제됨)

### 수정 (4개)
1. 📝 `build.gradle` - docker-java 의존성 추가
2. 📝 `org.brown.nanogridplus.config.AgentConfig` - DockerConfig 내부 클래스 추가
3. 📝 `application.yml` - docker 설정 추가
4. 📝 `org.brown.nanogridplus.sqs.SqsPoller` - DockerService 연동

---

## ✅ 구현 체크리스트

- ✅ build.gradle에 docker-java 의존성 추가
- ✅ ExecutionResult DTO 생성
  - ✅ 모든 필드 정의
  - ✅ Builder 패턴 지원
  - ✅ 커스텀 toString()
- ✅ AgentConfig에 DockerConfig 추가
- ✅ application.yml에 docker 설정 추가
- ✅ DockerConfig 클래스 생성 (DockerClient Bean)
- ✅ DockerService 인터페이스 정의
- ✅ DockerEngineService 구현
  - ✅ 이미지 선택 로직
  - ✅ 컨테이너 생성 (볼륨 마운트)
  - ✅ 컨테이너 실행 및 대기
  - ✅ 로그 수집 (stdout/stderr 분리)
  - ✅ 컨테이너 정리 (stop + remove)
  - ✅ 에러 처리
  - ✅ TODO 주석 (타임아웃, Warm Pool, Auto-Tuner)
- ✅ SqsPoller에 DockerService 주입
- ✅ SqsPoller에서 runTask 호출
- ✅ 빌드 성공 (BUILD SUCCESSFUL)

---

## 🚀 성능 및 특징

### 현재 구현의 특징
1. **매 요청마다 새 컨테이너**
   - 격리 보장
   - 클린한 실행 환경
   - 단점: Cold Start 시간

2. **볼륨 마운트**
   - 호스트 파일 시스템 사용
   - 빠른 파일 접근
   - 컨테이너 삭제 후에도 파일 유지

3. **로그 수집**
   - stdout/stderr 완전 분리
   - 스트리밍 방식으로 수집
   - 최대 30초 대기

4. **정리 보장**
   - finally 블록에서 cleanup
   - 강제 삭제 (force=true)
   - 리소스 누수 방지

---

## 🔜 다음 단계 (4단계: Warm Pool)

### 준비 완료 ✅
- Docker 컨테이너 생성/실행 완성
- 로그 수집 완성
- 에러 처리 완성

### 다음 구현 사항
**4단계: Warm Pool**
1. 컨테이너 미리 생성
   - python-base, gcc-base 이미지
   - Pool 크기 설정 (예: 5개)
2. Pause/Unpause 활용
   - 컨테이너 재사용
   - Cold Start 제거
3. Pool 관리
   - acquireContainer()
   - releaseContainer()
   - 자동 보충

**5단계: Auto-Tuner**
1. cgroups 메모리 사용량 측정
2. docker stats 수집
3. 최적 메모리 크기 계산
4. 메모리 제한 설정

**6단계: Redis Publish**
1. ExecutionResult를 Redis에 Publish
2. Control Plane에서 수집
3. 메트릭 대시보드 연동

---

## 📈 구현 진행 상황

| 단계 | 기능 | 상태 | 완료일 |
|------|------|------|--------|
| 0단계 | 프로젝트 골격 | ✅ 완료 | 2025-11-30 |
| 1단계 | SQS Long Polling | ✅ 완료 | 2025-11-30 |
| 2단계 | S3 Downloader | ✅ 완료 | 2025-11-30 |
| **3단계** | **Docker Orchestrator** | **✅ 완료** | **2025-11-30** |
| 4단계 | Warm Pool | 🚧 예정 | - |
| 5단계 | Auto-Tuner | 🚧 예정 | - |
| 6단계 | Redis 메트릭 | 🚧 예정 | - |

---

## 🎉 요약

**3단계 Docker Orchestrator 구현이 성공적으로 완료되었습니다!**

### 핵심 성과
- ✅ docker-java 통합 완료
- ✅ Docker 컨테이너 생성/실행/정리
- ✅ 런타임별 이미지 선택 (python, cpp)
- ✅ 볼륨 마운트 (workDir → /workspace)
- ✅ stdout/stderr 분리 수집
- ✅ exitCode 및 실행 시간 측정
- ✅ ExecutionResult DTO 완성
- ✅ SqsPoller 완전 통합
- ✅ 빌드 성공, 에러 0개

### End-to-End 파이프라인 완성
```
SQS → S3 Download → Docker Execution → Log Collection → Result
```

### 코드 품질
- 🏆 명확한 책임 분리
- 🏆 에러 처리 완비
- 🏆 리소스 정리 보장 (finally)
- 🏆 확장 가능한 구조 (TODO 주석)
- 🏆 상세한 로깅

이제 4단계 Warm Pool 구현으로 Cold Start를 제거할 준비가 완료되었습니다! 🚀

---

**구현 완료일**: 2025-11-30  
**버전**: 0.3 (0~3단계 완료)  
**팀**: NanoGrid Plus Team  
**다음 단계**: Warm Pool (4단계)

