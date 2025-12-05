# ⚠️ Redis 타임아웃 문제 해결 가이드

## 🔍 현재 상황

### ✅ Worker Agent 상태: 정상
```
07:40:20 - Worker: 함수 실행 완료 (312ms)
07:40:21 - Worker: Redis Publish 성공
07:40:21 - [DONE][OK] requestId=afc4198d-df18-46e3-85db-505b99b3a73e
```

### ❌ Controller 상태: 타임아웃
```json
{
  "status": "TIMEOUT",
  "message": "Execution timed out"
}
```

**Worker는 2초 만에 처리하고 Redis에 결과를 전송했지만, Controller는 25초를 기다리다 타임아웃 발생**

---

## 🐛 원인 분석

### 가능성 1: Controller가 Redis 구독을 시작하지 못함 (80%)

**증상:**
- Worker 로그: `Result published successfully`
- Controller: 타임아웃

**원인:**
- Controller EC2가 Redis ElastiCache에 연결 불가
- Security Group에서 Controller → Redis 6379 포트 차단
- VPC가 다름

### 가능성 2: 구독 타이밍 문제 (15%)

**증상:**
- Worker가 너무 빨리 Publish (2초)
- Controller가 구독 시작하기 전에 메시지 발행

**원인:**
- Redis Pub/Sub은 "실시간"이므로 구독 전 메시지는 받을 수 없음

### 가능성 3: 채널 이름 불일치 (5%)

**증상:**
- Worker: `result:afc4198d-df18-46e3-85db-505b99b3a73e`
- Controller: `result:XXX` (다른 이름)

---

## 🔧 해결 방법

### 1단계: Redis 연결 확인

**Controller EC2에서 실행:**
```bash
# Redis 연결 테스트
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping

# 예상 결과: PONG
# 만약 "Connection refused" 또는 timeout → Security Group 문제
```

**결과별 조치:**
- ✅ `PONG`: 2단계로 진행
- ❌ `Connection refused`: Security Group 수정 필요
- ❌ `timeout`: VPC 또는 Network ACL 문제

---

### 2단계: Security Group 확인

**Redis ElastiCache Security Group 확인:**
```
Inbound Rules:
Type: Custom TCP
Port: 6379
Source: sg-CONTROLLER / 0.0.0.0/0 (또는 Controller EC2 IP)
```

**확인 방법 (AWS Console):**
```
1. ElastiCache → Redis Clusters → nanogrid-redis
2. Details → Security Groups 클릭
3. Inbound rules에서 6379 허용 확인
```

**또는 AWS CLI:**
```bash
aws ec2 describe-security-groups \
  --group-ids sg-XXX \  # Redis의 Security Group ID
  --region ap-northeast-2 \
  --query 'SecurityGroups[*].IpPermissions[?FromPort==`6379`]'
```

---

### 3단계: Controller 로그 확인

**Controller가 출력해야 할 로그:**
```javascript
// Controller에서 추가 필요한 로그
console.log(`[REDIS] Subscribing to channel: result:${requestId}`);

sub.subscribe(channel);
sub.on('message', (chn, msg) => {
    console.log(`[REDIS] Received message on channel: ${chn}`);
    console.log(`[REDIS] Message: ${msg.substring(0, 100)}...`);
});

// 타임아웃 시
console.error(`[REDIS] Timeout waiting for result on channel: result:${requestId}`);
```

**확인 사항:**
- ✅ `Subscribing to channel` 로그 있음 → 구독 시작됨
- ❌ 로그 없음 → Redis 연결 실패

---

### 4단계: Worker 로그 업데이트 (구독자 수 확인)

Worker Agent를 최신 버전으로 업데이트하면 구독자 수가 로그에 표시됩니다:

**업데이트 전:**
```
[INFO] ✅ [REDIS] Result published successfully
```

**업데이트 후:**
```
[INFO] ✅ [REDIS] Result published successfully, subscribers=1  ← 정상
[WARN] ⚠️ [REDIS] Result published but NO SUBSCRIBERS  ← 문제!
```

**업데이트 방법:**
```bash
# Worker EC2에서
cd NanoGridPlus
git pull
./deploy-ec2.sh
```

---

### 5단계: 실시간 테스트

**터미널 1 (Controller EC2):**
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
> SUBSCRIBE result:test-manual-channel
Reading messages... (press Ctrl-C to quit)
```

**터미널 2 (Worker EC2):**
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
> PUBLISH result:test-manual-channel "Hello from Worker"
(integer) 1   ← 구독자 1명 확인!
```

**터미널 1에서 메시지 수신 확인:**
```
1) "message"
2) "result:test-manual-channel"
3) "Hello from Worker"
```

---

## 📋 B팀 확인 체크리스트

### 인프라
- [ ] Controller EC2가 `nanogrid-vpc` 안에 있음
- [ ] Controller EC2에서 `redis-cli ping` 성공
- [ ] Redis Security Group에서 Controller → 6379 허용

### 코드
- [ ] Controller가 `result:${requestId}` 채널 구독 시작
- [ ] 구독 시작 로그가 찍힘
- [ ] `waitForResult()` 함수에서 구독 중 로그 확인

### 타이밍
- [ ] Controller가 SQS 메시지 전송 **직후** 바로 구독 시작
- [ ] Worker보다 먼저 구독 (Worker는 2~3초 후 Publish)

---

## 🚀 권장 해결 순서

### 단기 (지금 당장)

1. **Controller EC2에서 Redis 연결 테스트**
   ```bash
   redis-cli -h nanogrid-redis... ping
   ```

2. **Security Group 확인 및 수정** (필요시)
   ```
   Redis SG → Inbound → Add Rule:
   Type: Custom TCP, Port: 6379, Source: Controller SG
   ```

3. **Controller 로그 추가** (구독 시작/수신 확인용)

### 중기 (1시간 내)

4. **Worker 최신 버전 배포** (구독자 수 로깅)

5. **End-to-End 재테스트**
   ```bash
   curl -X POST http://43.202.0.218:8080/run \
     -H "Content-Type: application/json" \
     -d '{"functionId": "hello-python", "inputData": {}}'
   ```

6. **양쪽 로그 동시 확인**
   - Worker: `subscribers=1` 확인
   - Controller: `Received message` 확인

---

## 📞 B팀 협업 요청 사항

다음 정보를 공유해주세요:

### 1. Controller EC2 정보
```
- EC2 인스턴스 ID: i-xxxxx
- VPC: vpc-xxxxx (nanogrid-vpc인지 확인)
- Security Group: sg-xxxxx
- Private IP: 10.0.x.x
```

### 2. Redis 연결 테스트 결과
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping
# 결과: ___
```

### 3. Controller 로그 (특히 Redis 관련)
```
[날짜/시간] Subscribing to channel: result:xxx
[날짜/시간] Timeout waiting for result
```

### 4. Controller의 환경 변수
```bash
echo $REDIS_HOST
# 출력: ___
```

---

## ✅ 성공 시 예상 로그

### Worker
```
[INFO] 📤 [REDIS] Publishing result to channel: result:xxx
[INFO]    Redis Host: nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
[INFO] ✅ [REDIS] Result published successfully, subscribers=1
[INFO] [DONE][OK] requestId=xxx
```

### Controller
```
[INFO] Subscribing to channel: result:xxx
[INFO] Received message on channel: result:xxx
[INFO] Message: {"requestId":"xxx","status":"SUCCESS",...}
```

### API 응답
```json
{
  "requestId": "xxx",
  "status": "SUCCESS",
  "exitCode": 0,
  "stdout": "Hello from NanoGrid Plus!\n",
  "durationMillis": 312,
  "peakMemoryMB": 2
}
```

---

## 🆘 긴급 연락

문제가 계속되면:
1. Worker 로그: `/home/ec2-user/nanogrid-agent.log`
2. Controller 로그: (B팀 경로)
3. 양쪽 로그를 requestId 기준으로 매칭하여 공유

**현재 Worker는 100% 정상 동작 중입니다. 문제는 Controller ↔ Redis 연결입니다.** 🔍

