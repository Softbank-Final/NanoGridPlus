# Redis 통합 완료 가이드

## ✅ 구현 완료

NanoGrid Plus Worker Agent는 이제 **Redis Pub/Sub**을 통해 B팀 Controller와 완전히 연동됩니다.

---

## 🔄 전체 흐름

```
1. B팀 Controller: POST /run 요청 받음
   ↓
2. Controller: SQS에 작업 메시지 전송
   ↓
3. Worker Agent: SQS Long Polling으로 메시지 수신
   ↓
4. Worker Agent: S3에서 코드 다운로드
   ↓
5. Worker Agent: Docker Warm Pool 컨테이너로 실행
   ↓
6. Worker Agent: 메모리 사용량 측정 + CloudWatch 전송
   ↓
7. Worker Agent: Redis `result:{requestId}` 채널에 결과 Publish ⭐ (새로 추가)
   ↓
8. B팀 Controller: Redis 구독 중 결과 수신 (25초 타임아웃)
   ↓
9. Controller: 사용자에게 응답 반환
```

---

## 📦 추가된 구성 요소

### 1. Redis 의존성 (`build.gradle`)

```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-redis'
```

### 2. Redis 설정 클래스 (`RedisConfig.java`)

```java
@Configuration
public class RedisConfig {
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // B팀 제공 Redis 엔드포인트 연결
    }
    
    @Bean
    public StringRedisTemplate stringRedisTemplate() {
        // Redis Pub/Sub용 템플릿
    }
}
```

### 3. Redis Publisher (`RedisResultPublisher.java`)

```java
@Service
public class RedisResultPublisher {
    public void publishResult(ExecutionResult result) {
        String channel = "result:" + requestId;
        redisTemplate.convertAndSend(channel, jsonMessage);
    }
}
```

### 4. SqsPoller 통합

실행 완료 후 자동으로 Redis에 결과 전송:

```java
ExecutionResult result = dockerService.runTask(taskMessage, workDir);
redisResultPublisher.publishResult(result); // ⭐ 추가
```

---

## ⚙️ 설정 (application.yml)

```yaml
agent:
  redis:
    host: nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
    port: 6379
    password: ""
    resultPrefix: "result:"
```

**중요**: B팀이 제공한 Redis ElastiCache 엔드포인트를 사용합니다.

---

## 📤 전송 데이터 형식

Worker가 Redis에 전송하는 JSON:

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "functionId": "hello-python",
  "status": "SUCCESS",
  "exitCode": 0,
  "stdout": "Hello from NanoGrid Plus!\nResult: 42\n",
  "stderr": "",
  "durationMillis": 232,
  "peakMemoryBytes": 6832128,
  "peakMemoryMB": 6,
  "optimizationTip": "💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(6MB)이 매우 낮습니다..."
}
```

---

## 🧪 테스트 방법

### 1. Worker Agent 실행

```bash
ssh ec2-user@YOUR_WORKER_EC2

cd NanoGridPlus
git pull
./deploy-ec2.sh

tail -f app.log
```

### 2. B팀 Controller를 통해 함수 실행

```bash
# Controller API 호출
curl -X POST http://43.202.0.218:8080/run \
  -H "Content-Type: application/json" \
  -d '{
    "functionId": "hello-python",
    "inputData": {}
  }'
```

### 3. 예상 로그 (Worker Agent)

```
[INFO] ===== 작업 메시지 수신 =====
[INFO] Received task: TaskMessage(requestId=xxx, functionId=hello-python, ...)
[INFO] Prepared working directory at: /tmp/task/xxx
[INFO] [DOCKER] Acquiring container from PYTHON pool
[INFO] [DOCKER] Executing task in container: yyy
[INFO] [AUTO-TUNER] Measured peak memory: 6832128 bytes
[INFO] 📤 [REDIS] Publishing result to channel: result:xxx (requestId=xxx)
[INFO] ✅ [REDIS] Result published successfully for requestId=xxx
[INFO] [DONE][OK] requestId=xxx
```

### 4. 예상 응답 (Controller)

```json
{
  "requestId": "xxx",
  "functionId": "hello-python",
  "status": "SUCCESS",
  "exitCode": 0,
  "stdout": "Hello from NanoGrid Plus!\nResult: 42\n",
  "durationMillis": 232,
  "peakMemoryMB": 6,
  "optimizationTip": "💡 Tip: ..."
}
```

---

## 🔧 트러블슈팅

### Redis 연결 실패

**증상**:
```
❌ [REDIS][FAIL] Failed to publish result
```

**해결**:
1. Worker EC2가 B팀 Redis ElastiCache와 같은 VPC에 있는지 확인
2. Security Group에서 6379 포트 허용 확인
3. application.yml의 Redis host 주소 확인

```bash
# Redis 연결 테스트
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping
# 응답: PONG
```

### Redis 전송은 성공했지만 Controller가 못 받음

**원인**: Controller가 다른 채널을 구독 중

**확인**:
- Worker 로그: `result:xxx` 채널에 Publish
- Controller 로그: 동일한 채널 구독 중인지 확인

---

## ✅ 연동 체크리스트

- [x] Redis 의존성 추가 (`build.gradle`)
- [x] RedisConfig 설정 클래스 생성
- [x] RedisResultPublisher 서비스 구현
- [x] SqsPoller에 Redis Publisher 통합
- [x] application.yml에 Redis 설정 추가
- [x] 빌드 성공 확인
- [ ] EC2에 배포 (다음 단계)
- [ ] B팀 Controller와 End-to-End 테스트 (다음 단계)

---

## 🚀 다음 단계

1. **EC2 배포**:
   ```bash
   cd NanoGridPlus
   ./deploy-ec2.sh
   ```

2. **B팀과 협업 테스트**:
   - B팀 Controller에서 `/run` API 호출
   - Worker 로그에서 Redis Publish 확인
   - Controller 응답에서 결과 확인

3. **최종 검증**:
   - Happy Path (Python, C++ 함수 실행)
   - Error Case (존재하지 않는 S3 키, 잘못된 런타임)
   - Auto-Tuner 최적화 팁 표시

---

## 📞 B팀 연동 정보

| 항목 | 값 |
|------|-----|
| **SQS Queue URL** | `https://sqs.ap-northeast-2.amazonaws.com/769213334367/nanogrid-task-queue` |
| **Redis Host** | `nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com` |
| **Redis Port** | `6379` |
| **Redis Channel Format** | `result:{requestId}` |
| **Controller IP** | `43.202.0.218:8080` |
| **VPC** | `nanogrid-vpc` |

---

## 📚 관련 문서

- [README.md](./README.md) - 프로젝트 전체 개요
- [EC2_DEPLOYMENT.md](./EC2_DEPLOYMENT.md) - 배포 가이드
- [TESTING_GUIDE.md](./TESTING_GUIDE.md) - 테스트 시나리오

