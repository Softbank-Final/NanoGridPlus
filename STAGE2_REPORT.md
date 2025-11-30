# NanoGrid Plus - 2단계 구현 완료 보고서

## ✅ 2단계: S3 Downloader 구현 완료

### 구현 일자
2025-11-30

### 구현 범위
**S3에서 코드(zip)를 다운로드하고 작업 디렉터리에 압축 해제하는 기능 완성**

---

## 📋 구현 내역

### 1. AgentConfig 확장 ✅

**파일**: `org.brown.nanogridplus.config.AgentConfig`

**추가된 필드**:
```java
private String taskBaseDir = "/tmp/task";  // 작업 디렉터리 기본 경로
```

**application.yml 설정**:
```yaml
agent:
  taskBaseDir: /tmp/task
  polling:
    enabled: true
    fixedDelayMillis: 1000
```

---

### 2. AwsConfig에 S3Client Bean 추가 ✅

**파일**: `org.brown.nanogridplus.config.AwsConfig`

**추가된 Bean**:
```java
@Bean
public S3Client s3Client() {
    return S3Client.builder()
            .region(Region.of(agentProperties.getRegion()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
}
```

---

### 3. CodeStorageService 인터페이스 생성 ✅

**파일**: `org.brown.nanogridplus.s3.CodeStorageService`

**메서드 시그니처**:
```java
public interface CodeStorageService {
    /**
     * 주어진 TaskMessage에 해당하는 코드 zip을 S3에서 다운로드하여
     * 작업 디렉터리에 압축 해제하고, 해당 작업 디렉터리의 Path를 반환한다.
     */
    Path prepareWorkingDirectory(TaskMessage taskMessage);
}
```

---

### 4. S3CodeStorageService 구현 클래스 생성 ✅

**파일**: `org.brown.nanogridplus.s3.S3CodeStorageService`

#### 주요 기능

##### 4.1) S3 버킷 결정 로직
```java
private String determineS3Bucket(TaskMessage taskMessage)
```
- **우선순위 1**: TaskMessage에 포함된 `s3Bucket`
- **우선순위 2**: AgentProperties의 기본 `codeBucketName`

##### 4.2) 작업 디렉터리 생성
```java
private Path createWorkingDirectory(String requestId)
```
- 경로: `{taskBaseDir}/{requestId}`
- 예: `/tmp/task/uuid-string`
- 기존 디렉터리 존재 시 삭제 후 재생성 (깨끗한 상태 보장)

##### 4.3) S3에서 zip 다운로드
```java
private Path downloadFromS3(String bucket, String key, Path workingDir, String requestId)
```
- S3Client 사용
- `GetObjectRequest` 생성
- `ResponseTransformer.toFile()` 사용하여 직접 파일로 저장
- 경로: `{workingDir}/code.zip`

##### 4.4) zip 압축 해제
```java
private void extractZipFile(Path zipFilePath, Path targetDir, String requestId)
```
- `ZipInputStream` 사용
- 디렉터리 구조 유지
- 디렉터리 순회 공격(Path Traversal) 방지
  - `targetPath.normalize().startsWith(targetDir.normalize())` 검증
- 부모 디렉터리 자동 생성
- 파일 개수 카운팅 및 로그 출력

##### 4.5) 에러 처리
- S3 다운로드 실패 시 상세한 에러 로그
- requestId, s3Bucket, s3Key 정보 포함
- `RuntimeException` 던져서 상위에서 처리

##### 4.6) 디렉터리 재귀 삭제
```java
private void deleteDirectory(Path directory)
```
- `Files.walk()` 사용
- 역순 정렬로 파일 먼저 삭제, 디렉터리 나중 삭제

---

### 5. SqsPoller에 S3 다운로드 연결 ✅

**파일**: `org.brown.nanogridplus.sqs.SqsPoller`

**변경 사항**:

1. **의존성 주입 추가**:
```java
private final CodeStorageService codeStorageService;
```

2. **processMessage 메서드에 S3 다운로드 호출 추가**:
```java
// 2단계: S3에서 코드 다운로드 및 작업 디렉터리 준비
Path workDir = codeStorageService.prepareWorkingDirectory(taskMessage);
log.info("Prepared working directory for request {} at path: {}", 
        taskMessage.getRequestId(), workDir);
```

3. **에러 처리 개선**:
- 메시지 파싱 실패 → "메시지 처리 실패"로 변경 (S3 다운로드 실패 포함)
- 실패한 메시지는 삭제하지 않음 (DLQ로 이동)

---

## 🎯 실행 흐름

### 정상 실행 시나리오

1. **SQS 메시지 수신**
   - SqsPoller가 Long Polling으로 메시지 수신
   
2. **메시지 파싱**
   - JSON → TaskMessage 객체 변환
   
3. **작업 디렉터리 준비**
   - S3 버킷 결정 (우선순위: TaskMessage → AgentProperties)
   - 작업 디렉터리 생성: `/tmp/task/{requestId}`
   
4. **S3 다운로드**
   - S3에서 zip 파일 다운로드
   - 임시 파일: `/tmp/task/{requestId}/code.zip`
   
5. **압축 해제**
   - zip 파일을 작업 디렉터리에 압축 해제
   - 디렉터리 구조 유지
   - Path Traversal 공격 방지
   
6. **정리**
   - zip 파일 삭제
   - 작업 디렉터리 Path 반환
   
7. **메시지 삭제**
   - SQS에서 메시지 삭제 (정상 처리 완료)

---

## 📊 로그 출력 예시

### 성공 시나리오
```
INFO o.b.n.sqs.SqsPoller : ===== 작업 메시지 수신 =====
INFO o.b.n.sqs.SqsPoller : Received task: TaskMessage[requestId=test-001, ...]
INFO o.b.n.sqs.SqsPoller :   - Request ID: test-001
INFO o.b.n.sqs.SqsPoller :   - Function ID: func-01
INFO o.b.n.sqs.SqsPoller :   - Runtime: python
INFO o.b.n.sqs.SqsPoller :   - S3 Location: s3://nanogrid-code-bucket/func-01/v1.zip
INFO o.b.n.sqs.SqsPoller :   - Timeout: 5000ms
INFO o.b.n.sqs.SqsPoller : ============================
INFO o.b.n.s3.S3CodeStorageService : Preparing working directory for request: test-001
INFO o.b.n.s3.S3CodeStorageService :   - S3 Bucket: nanogrid-code-bucket
INFO o.b.n.s3.S3CodeStorageService :   - S3 Key: func-01/v1.zip
INFO o.b.n.s3.S3CodeStorageService : Created working directory: /tmp/task/test-001
INFO o.b.n.s3.S3CodeStorageService : Downloading from S3: s3://nanogrid-code-bucket/func-01/v1.zip -> /tmp/task/test-001/code.zip
INFO o.b.n.s3.S3CodeStorageService : Successfully downloaded zip file: 1234567 bytes
INFO o.b.n.s3.S3CodeStorageService : Extracting zip file: /tmp/task/test-001/code.zip -> /tmp/task/test-001
INFO o.b.n.s3.S3CodeStorageService : Successfully extracted 5 files from zip for requestId=test-001
INFO o.b.n.s3.S3CodeStorageService : Successfully prepared working directory: /tmp/task/test-001
INFO o.b.n.sqs.SqsPoller : Prepared working directory for request test-001 at path: /tmp/task/test-001
```

### 실패 시나리오
```
ERROR o.b.n.s3.S3CodeStorageService : Failed to download from S3: s3://nanogrid-code-bucket/func-01/v1.zip for requestId=test-001
ERROR o.b.n.sqs.SqsPoller : 메시지 처리 실패. 메시지 내용: {...}
```

---

## 🧪 테스트 방법

### 1. 로컬 테스트 (S3 없이)

작업 디렉터리 생성 테스트:
```bash
# /tmp/task 디렉터리 권한 확인
mkdir -p /tmp/task
ls -la /tmp/task
```

### 2. S3 통합 테스트

#### 2.1) 테스트 zip 파일 준비
```bash
# 테스트 코드 작성
mkdir -p test-code
echo "print('Hello from NanoGrid')" > test-code/main.py
echo "requirements.txt content" > test-code/requirements.txt

# zip 압축
cd test-code
zip -r ../test-code.zip .
cd ..
```

#### 2.2) S3에 업로드
```bash
aws s3 cp test-code.zip s3://nanogrid-code-bucket/test/test-code.zip
```

#### 2.3) SQS 메시지 전송
```bash
aws sqs send-message \
  --queue-url YOUR_QUEUE_URL \
  --message-body '{
    "requestId": "test-s3-download-001",
    "functionId": "test-func",
    "runtime": "python",
    "s3Bucket": "nanogrid-code-bucket",
    "s3Key": "test/test-code.zip",
    "timeoutMs": 5000
  }'
```

#### 2.4) 결과 확인
```bash
# 작업 디렉터리 확인
ls -la /tmp/task/test-s3-download-001/

# 파일 내용 확인
cat /tmp/task/test-s3-download-001/main.py
cat /tmp/task/test-s3-download-001/requirements.txt
```

---

## 🔒 보안 고려사항

### 1. Path Traversal 공격 방지
```java
// zip 엔트리 검증
if (!targetPath.normalize().startsWith(targetDir.normalize())) {
    log.warn("Suspicious zip entry detected, skipping: {}", entry.getName());
    continue;
}
```

### 2. AWS 자격 증명
- `DefaultCredentialsProvider` 사용
- 환경 변수, IAM Role, AWS Profile 지원
- 코드에 하드코딩 금지

### 3. 디렉터리 격리
- 각 requestId마다 독립된 디렉터리
- 기존 디렉터리 삭제 후 재생성

---

## 📦 생성된/수정된 파일

### 신규 생성 (2개)
1. `org.brown.nanogridplus.s3.CodeStorageService` (인터페이스)
2. `org.brown.nanogridplus.s3.S3CodeStorageService` (구현)

### 수정 (4개)
1. `org.brown.nanogridplus.config.AgentConfig` - taskBaseDir 추가
2. `org.brown.nanogridplus.config.AwsConfig` - S3Client Bean 추가
3. `org.brown.nanogridplus.sqs.SqsPoller` - CodeStorageService 연결
4. `application.yml` - taskBaseDir 설정 추가

---

## ✅ 완료 체크리스트

- ✅ AgentConfig에 taskBaseDir 필드 추가
- ✅ application.yml에 taskBaseDir 설정 추가
- ✅ AwsConfig에 S3Client Bean 추가
- ✅ CodeStorageService 인터페이스 생성
- ✅ S3CodeStorageService 구현
  - ✅ S3 버킷 결정 로직 (우선순위)
  - ✅ 작업 디렉터리 생성
  - ✅ S3 다운로드
  - ✅ zip 압축 해제
  - ✅ Path Traversal 방지
  - ✅ 에러 처리 및 로깅
  - ✅ 디렉터리 재귀 삭제
- ✅ SqsPoller에 CodeStorageService 주입
- ✅ SqsPoller에서 prepareWorkingDirectory 호출
- ✅ 빌드 성공 확인
- ✅ 에러 없음 확인

---

## 🔜 다음 단계 (3단계: Docker 실행)

### 준비 완료
- ✅ 작업 디렉터리가 준비됨 (`Path workDir`)
- ✅ 코드 파일들이 압축 해제됨

### 다음 구현 사항
1. **DockerService 구현**
   - Docker Java Client 통합
   - 컨테이너 생성 및 실행
   - 볼륨 마운트 (workDir → 컨테이너)
   - cgroups 리소스 제한

2. **WarmPoolManager 구현**
   - python-base, gcc-base 이미지 준비
   - 컨테이너 미리 생성 (Warm Pool)
   - Pool에서 꺼내서 사용

3. **실행 결과 수집**
   - stdout, stderr 캡처
   - exit code 확인
   - 실행 시간 측정

---

## 📈 구현 진행 상황

| 단계 | 기능 | 상태 |
|------|------|------|
| 0단계 | 프로젝트 골격 생성 | ✅ 완료 |
| 1단계 | SQS Long Polling | ✅ 완료 |
| **2단계** | **S3 Downloader** | **✅ 완료** |
| 3단계 | Docker 실행 | 🚧 예정 |
| 4단계 | Warm Pool | 🚧 예정 |
| 5단계 | Auto-Tuner | 🚧 예정 |
| 6단계 | Redis 메트릭 | 🚧 예정 |

---

## 🎉 요약

**2단계 S3 Downloader 구현이 성공적으로 완료되었습니다!**

- ✅ S3에서 코드 zip 다운로드
- ✅ 작업 디렉터리 생성 및 관리
- ✅ zip 압축 해제 (디렉터리 구조 유지)
- ✅ Path Traversal 공격 방지
- ✅ 상세한 로그 및 에러 처리
- ✅ SqsPoller와 통합
- ✅ 빌드 성공

이제 3단계 Docker 실행을 구현할 준비가 완료되었습니다!

---

**구현 완료일**: 2025-11-30  
**버전**: 0.2 (0~2단계)  
**팀**: NanoGrid Plus Team

