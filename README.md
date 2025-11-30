# NanoGrid Plus - Smart Worker Agent (NanoAgent)

**Project NanoGrid Plus: Intelligent Hybrid FaaS**  
Data Plane (C) - EC2 기반 Smart Worker Agent

## 프로젝트 개요

해커톤 프로젝트로, EC2 위에서 실행되는 Smart Worker Agent(NanoAgent)입니다.

### 아키텍처

```
Control Plane (Dispatcher Lambda)
         ↓
      [SQS Queue]
         ↓
    EC2 Instance
    ┌─────────────────────────┐
    │  NanoAgent (Spring Boot) │
    │  - SQS Poller            │
    │  - S3 Code Downloader    │ (향후 구현)
    │  - Docker Orchestrator   │ (향후 구현)
    │  - Warm Pool Manager     │ (향후 구현)
    │  - Auto-Tuner            │ (향후 구현)
    │  - Redis Publisher       │ (향후 구현)
    └─────────────────────────┘
```

## 현재 구현 상태

### ✅ 0단계: 프로젝트 골격 생성 (완료)
- Spring Boot 3.x 기반
- Java 17
- Lombok 사용
- AWS SDK v2 (SQS, S3)
- 패키지 구조 설정

### ✅ 1단계: SQS 메시지 수신 구조 (완료)
- `SqsPoller`: SQS Long Polling 구현
- `TaskMessage`: 작업 메시지 DTO
- 메시지 수신 및 파싱
- 메시지 삭제 로직

### 🚧 향후 구현 예정
- S3 코드 다운로드 (`CodeStorageService`)
- Docker 컨테이너 실행 (`DockerService`)
- Warm Pool 관리 (`WarmPoolManager`)
- 리소스 모니터링 (`ResourceMonitor`)
- Redis를 통한 메트릭 전송

## 프로젝트 구조

```
org.brown.nanogridplus/
├── NanoGridPlusApplication.java   # Spring Boot Entry Point
├── config/
│   ├── AgentProperties.java       # AWS 설정 (SQS, S3)
│   ├── AgentConfig.java           # Agent 동작 설정
│   ├── AwsConfig.java             # AWS SDK 클라이언트 설정
│   └── JacksonConfig.java         # JSON 파싱 설정
├── model/
│   └── TaskMessage.java           # SQS 메시지 DTO
├── sqs/
│   └── SqsPoller.java             # SQS Long Polling 구현
├── s3/
│   └── CodeStorageService.java    # S3 코드 다운로드 (향후 구현)
├── docker/
│   └── DockerService.java         # Docker 실행 (향후 구현)
├── warmup/
│   └── WarmPoolManager.java       # Warm Pool 관리 (향후 구현)
└── metrics/
    └── ResourceMonitor.java       # 리소스 모니터링 (향후 구현)
```

## 설정

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: NanoGridPlus

# AWS 설정
aws:
  region: ap-northeast-2
  sqs:
    queueUrl: https://sqs.ap-northeast-2.amazonaws.com/123456789012/nanogrid-task-queue
  s3:
    codeBucketName: nanogrid-code-bucket

# Agent 설정
agent:
  polling:
    enabled: true
    fixedDelayMillis: 1000
  warmPool:
    size: 5
    pythonBaseImage: python:3.9-slim
    gccBaseImage: gcc:11
```

## 빌드 및 실행

### 빌드
```bash
./gradlew build
```

### 실행
```bash
./gradlew bootRun
```

또는:

```bash
java -jar build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar
```

## SQS 메시지 형식

NanoAgent가 수신하는 작업 메시지 JSON 스키마:

```json
{
  "requestId": "uuid-string",
  "functionId": "func-01",
  "runtime": "python",
  "s3Bucket": "code-bucket-name",
  "s3Key": "func-01/v1.zip",
  "timeoutMs": 5000
}
```

## 테스트 방법

### 1. AWS 자격 증명 설정

```bash
# AWS CLI 설정
aws configure

# 또는 환경 변수 설정
export AWS_ACCESS_KEY_ID=your_access_key
export AWS_SECRET_ACCESS_KEY=your_secret_key
export AWS_REGION=ap-northeast-2
```

### 2. SQS 큐 생성 (테스트용)

```bash
aws sqs create-queue --queue-name nanogrid-task-queue
```

큐 URL을 복사하여 `application.yml`에 설정합니다.

### 3. 테스트 메시지 전송

```bash
aws sqs send-message \
  --queue-url https://sqs.ap-northeast-2.amazonaws.com/YOUR_ACCOUNT_ID/nanogrid-task-queue \
  --message-body '{
    "requestId": "test-001",
    "functionId": "func-01",
    "runtime": "python",
    "s3Bucket": "test-bucket",
    "s3Key": "test/code.zip",
    "timeoutMs": 5000
  }'
```

### 4. 애플리케이션 실행 및 로그 확인

```bash
./gradlew bootRun
```

로그에서 다음과 같은 메시지를 확인:

```
INFO  o.b.n.sqs.SqsPoller : ===== 작업 메시지 수신 =====
INFO  o.b.n.sqs.SqsPoller : Received task: TaskMessage[requestId=test-001, functionId=func-01, ...]
INFO  o.b.n.sqs.SqsPoller : ============================
```

## 주요 컴포넌트

### SqsPoller
- `@Scheduled`를 사용한 주기적 폴링
- Long Polling (20초 대기)
- 한 번에 최대 10개 메시지 수신
- JSON 파싱 및 에러 처리
- 메시지 삭제

### AgentProperties
- `@ConfigurationProperties`를 사용한 설정 바인딩
- AWS Region, SQS Queue URL, S3 Bucket 등

### AwsConfig
- `SqsClient` Bean 생성
- `DefaultCredentialsProvider` 사용 (환경 변수, IAM Role 등)

## 향후 단계

### 2단계: S3 코드 다운로드
- S3Client를 사용하여 코드 zip 다운로드
- 로컬 임시 디렉터리에 압축 해제

### 3단계: Docker 실행
- Docker Java Client 통합
- 컨테이너 생성 및 코드 실행
- cgroups 리소스 제한 설정

### 4단계: Warm Pool
- python-base, gcc-base 컨테이너 미리 생성
- Cold Start 최소화

### 5단계: Auto-Tuner
- cgroups 메모리 사용량 측정
- 최적 메모리 크기 계산

### 6단계: Redis 메트릭 전송
- 실행 결과 및 메트릭을 Redis에 Publish
- Control Plane에서 수집

## 기술 스택

- **Framework**: Spring Boot 3.x
- **Language**: Java 17
- **Build Tool**: Gradle
- **AWS SDK**: AWS SDK for Java v2
- **JSON**: Jackson
- **Logging**: SLF4J + Logback
- **Utils**: Lombok

## 라이센스

This is a hackathon project.

---

**NanoGrid Plus Team**  
Version 0.1 - SQS Polling & Project Skeleton

