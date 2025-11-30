# NanoGrid Plus - 5단계 구현 완료 보고서

## ✅ 5단계: In-place Auto-Tuner 구현 완료

### 구현 일자
2025-11-30

### 구현 범위
**Docker 컨테이너 실행 후 메모리 사용량을 측정하고, CloudWatch로 전송하며, 비용 절감 팁을 생성하는 Auto-Tuner 완성**

---

## 📋 구현 내역

### 1. TaskMessage에 memoryMb 필드 추가 ✅

**파일**: `org.brown.nanogridplus.model.TaskMessage`

**추가된 필드**:
```java
@JsonProperty("memoryMb")
private Integer memoryMb;  // 할당된 메모리 (MB), 없으면 null
```

**JSON 스키마 예시**:
```json
{
  "requestId": "uuid-string",
  "functionId": "func-01",
  "runtime": "python",
  "s3Bucket": "code-bucket-name",
  "s3Key": "func-01/v1.zip",
  "timeoutMs": 5000,
  "memoryMb": 256
}
```

---

### 2. ExecutionResult에 Auto-Tuner 필드 추가 ✅

**파일**: `org.brown.nanogridplus.model.ExecutionResult`

**추가된 필드**:
```java
/**
 * 피크 메모리 사용량 (바이트 단위) - Auto-Tuner
 */
private Long peakMemoryBytes;

/**
 * 메모리 최적화 팁 - Auto-Tuner
 */
private String optimizationTip;
```

**toString() 업데이트**:
```java
"ExecutionResult[..., peakMemoryBytes=%s]"
```

---

### 3. ResourceMonitor 인터페이스 및 구현 ✅

#### 3.1) 인터페이스

**파일**: `org.brown.nanogridplus.metrics.ResourceMonitor`

```java
public interface ResourceMonitor {
    Long measurePeakMemoryBytes(String containerId);
}
```

#### 3.2) DockerStatsResourceMonitor 구현

**파일**: `org.brown.nanogridplus.metrics.DockerStatsResourceMonitor`

**주요 기능**:
1. **docker stats 사용**:
```java
dockerClient.statsCmd(containerId)
    .exec(new ResultCallback.Adapter<Statistics>() {
        @Override
        public void onNext(Statistics stats) {
            Long usage = stats.getMemoryStats().getUsage();
            memoryUsage.set(usage);
            // 한 번만 읽고 종료
        }
    });
```

2. **타임아웃 처리**:
   - `CountDownLatch` 사용
   - 최대 5초 대기
   - 타임아웃 시 null 반환

3. **에러 처리**:
   - 측정 실패 시 null 반환
   - 로그로 에러 기록
   - 메인 로직에 영향 없음

---

### 4. CloudWatch 메트릭 전송 ✅

#### 4.1) CloudWatch 의존성 추가

**파일**: `build.gradle`

```gradle
implementation 'software.amazon.awssdk:cloudwatch'
```

#### 4.2) CloudWatchClient Bean

**파일**: `org.brown.nanogridplus.config.AwsConfig`

```java
@Bean
public CloudWatchClient cloudWatchClient() {
    return CloudWatchClient.builder()
            .region(Region.of(agentProperties.getRegion()))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
}
```

#### 4.3) CloudWatchMetricsPublisher 구현

**파일**: `org.brown.nanogridplus.metrics.CloudWatchMetricsPublisher`

**주요 기능**:
```java
public void publishPeakMemory(String functionId, String runtime, Long peakMemoryBytes) {
    // Namespace: "NanoGrid/FunctionRunner"
    // MetricName: "PeakMemoryBytes"
    // Unit: Bytes
    // Dimensions: FunctionId, Runtime
    
    cloudWatchClient.putMetricData(request);
}
```

**특징**:
- peakMemoryBytes가 null이면 전송 안 함
- 에러 발생 시 예외를 삼키고 로그만 출력
- 메인 로직에 영향 없음

---

### 5. AutoTunerService 구현 ✅

**파일**: `org.brown.nanogridplus.metrics.AutoTunerService`

#### 주요 로직

```java
public String createOptimizationTip(TaskMessage taskMessage, Long peakMemoryBytes)
```

**메모리 비율 계산**:
```java
int allocatedMb = (taskMessage.getMemoryMb() != null) 
    ? taskMessage.getMemoryMb() 
    : DEFAULT_MEMORY_MB;  // 128MB

long allocatedBytes = allocatedMb * 1024L * 1024L;
double ratio = (double) peakMemoryBytes / (double) allocatedBytes;
```

**비율별 팁 생성**:

| 비율 | 상태 | 팁 내용 |
|------|------|---------|
| < 0.3 | 매우 낮음 | "💡 메모리를 줄이면 비용을 약 X% 절감 가능" |
| 0.3 ~ 0.7 | 여유 있음 | "✅ 더 절감하려면 X MB로 조정 가능" |
| 0.7 ~ 1.0 | 적절함 | "✅ 현재 메모리 설정이 적절합니다" |
| > 1.0 | 초과 | "⚠️ 메모리를 늘리는 것을 권장" |

**팁 예시**:
```
💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(64MB)이 매우 낮습니다. 
메모리를 96MB 정도로 줄이면 비용을 약 62% 절감할 수 있습니다.
```

---

### 6. DockerEngineService에 Auto-Tuner 통합 ✅

**파일**: `org.brown.nanogridplus.docker.DockerEngineService`

#### 6.1) 필드 추가
```java
private final ResourceMonitor resourceMonitor;
private final AutoTunerService autoTunerService;
private final CloudWatchMetricsPublisher metricsPublisher;
```

#### 6.2) runTask() 흐름

```java
@Override
public ExecutionResult runTask(TaskMessage taskMessage, Path workDir) {
    // 1. Warm Pool에서 컨테이너 획득
    String containerId = warmPoolManager.acquireContainer(runtimeType);
    
    // 2. docker exec 실행
    ExecResult execResult = executeInContainer(containerId, containerWorkDir, cmd);
    
    // 3. Auto-Tuner: 메모리 측정
    Long peakMemoryBytes = null;
    String optimizationTip = null;
    try {
        peakMemoryBytes = resourceMonitor.measurePeakMemoryBytes(containerId);
        
        // CloudWatch에 메트릭 전송
        metricsPublisher.publishPeakMemory(functionId, runtime, peakMemoryBytes);
        
        // 최적화 팁 생성
        optimizationTip = autoTunerService.createOptimizationTip(taskMessage, peakMemoryBytes);
        
    } catch (Exception e) {
        log.warn("Auto-Tuner failed, continuing without metrics", e);
    }
    
    // 4. ExecutionResult 생성
    return ExecutionResult.builder()
            .requestId(requestId)
            .functionId(functionId)
            .exitCode(exitCode)
            .stdout(stdout)
            .stderr(stderr)
            .durationMillis(durationMillis)
            .success(exitCode == 0)
            .peakMemoryBytes(peakMemoryBytes)  // NEW
            .optimizationTip(optimizationTip)  // NEW
            .build();
}
```

**에러 처리**:
- Auto-Tuner 실패 시에도 메인 로직 계속 진행
- peakMemoryBytes, optimizationTip은 null로 설정
- 실패 케이스에서도 동일하게 처리

---

### 7. SqsPoller 로그 업데이트 ✅

**파일**: `org.brown.nanogridplus.sqs.SqsPoller`

**변경 사항**:
```java
log.info("===== 실행 결과 =====");
log.info("Execution finished for request {}: exitCode={}, duration={}ms, peakMemory={}bytes",
        taskMessage.getRequestId(), result.getExitCode(), 
        result.getDurationMillis(), result.getPeakMemoryBytes());

if (result.getOptimizationTip() != null) {
    log.info("Optimization Tip: {}", result.getOptimizationTip());
}
log.info("============================");
```

---

## 🎯 전체 실행 흐름

```
1. SQS 메시지 수신
   └─ TaskMessage (memoryMb 포함)

2. S3 다운로드
   └─ /tmp/task/{requestId}

3. Warm Pool에서 컨테이너 획득
   └─ unpause

4. docker exec 실행
   └─ stdout, stderr, exitCode

5. Auto-Tuner 실행 ⭐
   ├─ ResourceMonitor.measurePeakMemoryBytes()
   │  └─ docker stats → 메모리 사용량
   │
   ├─ CloudWatchMetricsPublisher.publishPeakMemory()
   │  └─ Namespace: NanoGrid/FunctionRunner
   │      MetricName: PeakMemoryBytes
   │      Dimensions: FunctionId, Runtime
   │
   └─ AutoTunerService.createOptimizationTip()
       └─ 비율 계산 → 팁 생성

6. ExecutionResult 생성
   └─ peakMemoryBytes, optimizationTip 포함

7. 로그 출력
   └─ "Optimization Tip: ..."

8. Warm Pool에 컨테이너 반환
   └─ pause

9. SQS 메시지 삭제
```

---

## 📊 로그 출력 예시

### 성공 시나리오

```
INFO  SqsPoller : ===== 작업 메시지 수신 =====
INFO  SqsPoller : Received task: TaskMessage[requestId=req-001, functionId=hello-py, runtime=python, memoryMb=256]
INFO  DockerEngineService : Starting Warm Pool execution for request: req-001, runtime: python
INFO  DockerWarmPoolManager : Acquired and unpaused container: abc123...
INFO  DockerEngineService : Executing command in container abc123...: [python, main.py]
INFO  DockerStatsResourceMonitor : Measured peak memory for container abc123...: 67108864 bytes (64 MB)
INFO  CloudWatchMetricsPublisher : Publishing peak memory metric to CloudWatch: functionId=hello-py, runtime=python, bytes=67108864
INFO  CloudWatchMetricsPublisher : Successfully published peak memory metric to CloudWatch
INFO  AutoTunerService : Auto-Tuner analysis: functionId=hello-py, allocatedMb=256, peakMemoryBytes=67108864, ratio=0.25
INFO  AutoTunerService : Generated optimization tip: 💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(64MB)이 매우 낮습니다...
INFO  DockerEngineService : Container abc123... exec finished with exitCode: 0 in 1234ms
INFO  SqsPoller : ===== 실행 결과 =====
INFO  SqsPoller : Execution finished for request req-001: exitCode=0, duration=1234ms, peakMemory=67108864bytes
INFO  SqsPoller : Optimization Tip: 💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(64MB)이 매우 낮습니다. 메모리를 96MB 정도로 줄이면 비용을 약 62% 절감할 수 있습니다.
INFO  SqsPoller : ============================
```

---

## 🔧 CloudWatch 메트릭 확인

### AWS Console에서 확인
1. CloudWatch → 메트릭 → NanoGrid/FunctionRunner
2. MetricName: PeakMemoryBytes
3. Dimensions:
   - FunctionId: hello-py
   - Runtime: python

### 메트릭 쿼리 예시
```
SELECT AVG(PeakMemoryBytes) 
FROM "NanoGrid/FunctionRunner" 
WHERE FunctionId = 'hello-py'
```

---

## 📦 생성된/수정된 파일

### 신규 생성 (5개)
| 파일 | 설명 |
|------|------|
| `metrics/ResourceMonitor.java` | 인터페이스 |
| `metrics/DockerStatsResourceMonitor.java` | 구현체 ⭐ |
| `metrics/CloudWatchMetricsPublisher.java` | CloudWatch 전송 ⭐ |
| `metrics/AutoTunerService.java` | 최적화 팁 생성 ⭐ |
| `STAGE5_REPORT.md` | 상세 보고서 |

### 수정 (6개)
| 파일 | 변경 내용 |
|------|-----------|
| `TaskMessage.java` | memoryMb 필드 추가 |
| `ExecutionResult.java` | peakMemoryBytes, optimizationTip 추가 |
| `build.gradle` | CloudWatch 의존성 |
| `AwsConfig.java` | CloudWatchClient Bean |
| `DockerEngineService.java` | Auto-Tuner 통합 ⭐ |
| `SqsPoller.java` | 로그 업데이트 |

---

## ✅ 완료 체크리스트

### DTO 확장
- ✅ TaskMessage.memoryMb 추가
- ✅ ExecutionResult.peakMemoryBytes 추가
- ✅ ExecutionResult.optimizationTip 추가

### Auto-Tuner 구현
- ✅ ResourceMonitor 인터페이스
- ✅ DockerStatsResourceMonitor 구현
  - ✅ docker stats 사용
  - ✅ CountDownLatch 타임아웃
  - ✅ 에러 처리 (null 반환)
- ✅ AutoTunerService 구현
  - ✅ 메모리 비율 계산
  - ✅ 비율별 팁 생성 (4가지 케이스)
  - ✅ 한국어 자연스러운 메시지

### CloudWatch 통합
- ✅ CloudWatch 의존성 추가
- ✅ CloudWatchClient Bean 생성
- ✅ CloudWatchMetricsPublisher 구현
  - ✅ Namespace: NanoGrid/FunctionRunner
  - ✅ MetricName: PeakMemoryBytes
  - ✅ Dimensions: FunctionId, Runtime
  - ✅ 에러 처리 (예외 삼킴)

### DockerEngineService 통합
- ✅ 필드 주입 (3개)
- ✅ runTask에 Auto-Tuner 로직 추가
  - ✅ measurePeakMemoryBytes 호출
  - ✅ publishPeakMemory 호출
  - ✅ createOptimizationTip 호출
  - ✅ ExecutionResult에 필드 설정
  - ✅ try-catch 에러 처리
  - ✅ 실패 케이스에도 필드 추가

### 로깅
- ✅ SqsPoller 로그 업데이트
  - ✅ peakMemoryBytes 출력
  - ✅ optimizationTip 출력
- ✅ 빌드 성공 (BUILD SUCCESSFUL)

---

## 🚀 핵심 기능

### 1. 메모리 측정
- docker stats를 통한 실시간 측정
- 한 번의 샘플로 간단하게 측정
- 비동기 콜백 방식

### 2. CloudWatch 전송
- 커스텀 메트릭으로 전송
- Dimension으로 FunctionId, Runtime 분류
- 실패해도 메인 로직에 영향 없음

### 3. 최적화 팁
- 비율 기반 4단계 분류
- 구체적인 추천 메모리 크기
- 예상 비용 절감률 계산
- 사용자 친화적인 한국어 메시지

---

## 🔜 다음 단계 (6단계: Redis Publish)

### 준비 완료 ✅
- ExecutionResult에 모든 정보 포함
  - exitCode, stdout, stderr
  - durationMillis
  - peakMemoryBytes
  - optimizationTip

### 다음 구현 사항
**6단계: Redis Publish**
1. Redis Client 설정
2. RedisPublisher 구현
3. ExecutionResult → JSON 변환
4. Redis Pub/Sub 또는 Stream 전송
5. Control Plane에서 수신
6. Dashboard 연동

---

## 📈 구현 진행 상황

| 단계 | 기능 | Cold Start | 최적화 | 상태 |
|------|------|------------|--------|------|
| 0단계 | 골격 | - | - | ✅ |
| 1단계 | SQS | - | - | ✅ |
| 2단계 | S3 | - | - | ✅ |
| 3단계 | Docker | 3초 | - | ✅ |
| 4단계 | Warm Pool | 0.1초 | - | ✅ |
| **5단계** | **Auto-Tuner** | **0.1초** | **✅** | **✅** |
| 6단계 | Redis | 0.1초 | ✅ | 🚧 |

---

## 🎉 요약

**5단계 In-place Auto-Tuner 구현이 성공적으로 완료되었습니다!**

### 핵심 성과 🏆
- ✅ **메모리 측정** (docker stats)
- ✅ **CloudWatch 전송** (커스텀 메트릭)
- ✅ **최적화 팁 생성** (4단계 비율 분석)
- ✅ **비용 절감률 계산** (구체적인 추천)
- ✅ **한국어 메시지** (사용자 친화적)
- ✅ **에러 안전성** (실패해도 메인 로직 계속)

### 완성된 파이프라인
```
SQS → S3 → [Warm Pool] → docker exec → [Auto-Tuner] → Result
                                         ↓
                                    CloudWatch
                                    메트릭 전송
```

### 사용자 가치
- 🏆 **비용 가시성**: 실제 메모리 사용량 확인
- 🏆 **비용 절감**: 구체적인 추천으로 과금 최적화
- 🏆 **안정성 보장**: 메모리 부족 사전 경고
- 🏆 **자동 분석**: 별도 도구 없이 자동으로 분석

이제 **사용자에게 실질적인 비용 절감 가이드를 제공하는 지능형 FaaS**가 완성되었습니다!  
다음 단계에서 Redis를 통해 Control Plane과 연동하여 Dashboard에 표시하겠습니다! 🚀

---

**구현 완료일**: 2025-11-30  
**버전**: 0.5  
**팀**: NanoGrid Plus Team  
**성과**: **지능형 비용 최적화 완성!** 💡

