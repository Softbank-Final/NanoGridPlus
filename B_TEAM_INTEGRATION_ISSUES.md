# 🚨 B팀 긴급 협업 요청: Worker Agent 연동 이슈

> **발신**: C팀 (Worker Agent)  
> **수신**: B팀 (Controller)  
> **일시**: 2025-12-02  
> **상태**: 🔴 긴급 - 즉시 조치 필요

---

## 📋 요약

Worker Agent 로그 분석 결과, **2개의 연관된 문제**를 발견했습니다:

1. 🚨 **ZIP 파일이 비어있음** (34 bytes) → exitCode: 2
2. ⏱️ **Controller가 Redis 타임아웃** → 결과 수신 실패

**Worker Agent는 100% 정상 동작 중**이지만, Controller 측 이슈로 인해 통합이 완료되지 않았습니다.

---

## 🔍 문제 1: ZIP 파일이 비어있음 (주요 원인)

### 발견된 증거

```
07:40:19.996 [INFO] Successfully downloaded zip file: 34 bytes  ← 빈 ZIP!
07:40:19.997 [INFO] Successfully extracted 0 files from zip
07:40:19.997 [WARN] No files extracted from zip file. Empty archive?
07:40:20.018 [INFO] Executing command: [python, main.py]
07:40:20.480 [INFO] Container exec finished with exitCode: 2  ← 실패!
```

**34 bytes = 빈 ZIP 파일의 헤더만 존재**

### 원인 분석

**S3 경로**: `s3://nanogrid-code-bucket/functions/1daed6ee-7da5-4b8d-a367-0098bc204d12/v1.zip`

**가능한 원인:**

1. **사용자가 빈 ZIP을 업로드**
   ```bash
   # 잘못된 예시
   zip function.zip   # 파일 지정 안 함 → 34 bytes 빈 ZIP 생성
   ```

2. **Controller 업로드 로직 문제**
   - multer-s3가 파일 내용을 제대로 받지 못함
   - 업로드 과정에서 내용 손실

### 즉시 확인 요청

#### ① S3 ZIP 파일 직접 확인
```bash
# Controller 또는 Worker EC2에서
aws s3 cp s3://nanogrid-code-bucket/functions/1daed6ee-7da5-4b8d-a367-0098bc204d12/v1.zip /tmp/test.zip

ls -lh /tmp/test.zip
# 현재: 34 bytes (문제!)
# 정상: 200+ bytes

unzip -l /tmp/test.zip
# 현재: 빈 파일 목록
# 정상: main.py 등이 보여야 함
```

#### ② Controller 업로드 로그 확인

Controller의 `/upload` 엔드포인트에 로깅 추가 필요:

```javascript
app.post('/upload', upload.single('file'), async (req, res) => {
    console.log('📦 [UPLOAD] File received:');
    console.log('  - Original name:', req.file.originalname);
    console.log('  - Size:', req.file.size, 'bytes'); // ⚠️ 34면 문제!
    console.log('  - S3 Key:', req.file.key);
    
    if (req.file.size < 100) {
        console.error('⚠️ WARNING: File too small! Possible empty ZIP.');
    }
    
    // ... 기존 로직
});
```

### 해결 방법

#### 테스트용 올바른 ZIP 생성 및 업로드

```bash
# 1. 테스트 함수 생성
mkdir -p /tmp/test-function
cd /tmp/test-function

cat > main.py <<'EOF'
#!/usr/bin/env python3
print("Hello from NanoGrid Plus!")
print("Test function is working!")
print("Result: 42")
EOF

# 2. 올바른 방법으로 ZIP 생성
zip function.zip main.py

# 3. 크기 확인 (200-300 bytes 예상)
ls -lh function.zip

# 4. 내용 확인
unzip -l function.zip
# 출력: main.py 보여야 함

# 5. S3 업로드 (테스트용)
aws s3 cp function.zip s3://nanogrid-code-bucket/functions/test-manual/v1.zip
```

#### Controller에서 테스트 함수 등록

DynamoDB에 메타데이터 추가:
```javascript
{
  functionId: "test-manual",
  s3Key: "functions/test-manual/v1.zip",
  runtime: "python"
}
```

#### 테스트 실행

```bash
curl -X POST http://43.202.0.218:8080/run \
  -H "Content-Type: application/json" \
  -d '{"functionId": "test-manual", "inputData": {}}'
```

**예상 결과**: 성공! (exitCode: 0)

---

## 🔍 문제 2: Redis 타임아웃 (연관 문제)

### 발견된 증거

**Worker 로그 (정상):**
```
07:40:20.659 [INFO] 📤 [REDIS] Publishing result to channel: result:afc4198d...
07:40:21.950 [INFO] ✅ [REDIS] Result published successfully
07:40:21.976 [INFO] [DONE][OK] requestId=afc4198d...
```

**Controller 응답 (실패):**
```json
{
  "status": "TIMEOUT",
  "message": "Execution timed out"
}
```

**타임라인:**
```
07:40:19 - Controller: /run 호출, SQS 전송
07:40:20 - Worker: 855ms 만에 처리 완료
07:40:21 - Worker: Redis Publish 성공 ✅
07:40:44 - Controller: 25초 타임아웃 ❌
```

### 원인 분석

**가능성 1: ZIP 문제로 인한 FAILED 상태** (60%)
- Worker는 exitCode: 2로 Redis에 **FAILED 상태** 전송
- Controller가 FAILED를 제대로 처리하지 못하고 타임아웃
- 또는 채널 구독이 실패 상태 메시지를 못 받음

**가능성 2: Controller ↔ Redis 연결 문제** (30%)
- Controller EC2가 Redis ElastiCache에 연결 불가
- Security Group에서 6379 포트 차단
- VPC가 다름

**가능성 3: 구독 타이밍 문제** (10%)
- Controller가 구독 시작 전에 Worker가 Publish
- 하지만 Worker가 2초 만에 완료했으므로 가능성 낮음

### 즉시 확인 요청

#### ① Redis 연결 테스트

**Controller EC2에서 실행:**
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping

# 예상 결과: PONG
# 만약 실패 → Security Group 또는 VPC 문제
```

#### ② Controller 로그 확인

**필요한 로그 (추가 요청):**

```javascript
// Redis 구독 시작
console.log(`[REDIS] Subscribing to channel: result:${requestId}`);

sub.subscribe(channel);

sub.on('message', (chn, msg) => {
    console.log(`[REDIS] ✅ Received message on channel: ${chn}`);
    console.log(`[REDIS] Message preview: ${msg.substring(0, 100)}...`);
});

// 타임아웃 시
console.error(`[REDIS] ⏱️ Timeout waiting for result on channel: result:${requestId}`);
```

**확인 사항:**
- `Subscribing to channel` 로그가 있는가?
- `Received message` 로그가 있는가?
- 타임아웃 메시지만 있는가?

#### ③ Security Group 확인

**Redis ElastiCache Security Group:**
```
Inbound Rules에 다음이 있어야 함:
- Type: Custom TCP
- Port: 6379
- Source: Controller EC2의 Security Group 또는 IP
```

**확인 방법:**
1. AWS Console → ElastiCache → Redis Clusters → nanogrid-redis
2. Details → Security Groups 클릭
3. Inbound rules 확인

#### ④ 실시간 Pub/Sub 테스트

**터미널 1 (Controller EC2):**
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
> SUBSCRIBE result:test-channel
Reading messages...
```

**터미널 2 (Worker EC2):**
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
> PUBLISH result:test-channel "Hello from Worker"
(integer) 1  ← 구독자 1명이면 성공!
```

**터미널 1에서 메시지 수신 확인:**
```
1) "message"
2) "result:test-channel"
3) "Hello from Worker"
```

이 테스트 결과:
- ✅ **성공**: 네트워크는 정상, Controller 코드 문제
- ❌ **실패**: Security Group 또는 VPC 문제

---

## 📊 Worker가 전송한 실제 데이터

Worker는 다음 JSON을 Redis `result:afc4198d-df18-46e3-85db-505b99b3a73e` 채널에 **성공적으로 전송**했습니다:

```json
{
  "requestId": "afc4198d-df18-46e3-85db-505b99b3a73e",
  "functionId": "1daed6ee-7da5-4b8d-a367-0098bc204d12",
  "status": "FAILED",  ← exitCode: 2 때문에
  "exitCode": 2,
  "stdout": "",
  "stderr": "python: can't open file 'main.py': [Errno 2] No such file or directory",
  "durationMillis": 312,
  "peakMemoryBytes": 2887680,
  "peakMemoryMB": 2,
  "optimizationTip": "💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(2MB)이 매우 낮습니다..."
}
```

**Controller가 이 메시지를 받았는지 확인 필요!**

---

## ✅ 즉시 조치 사항 체크리스트

### B팀 (Controller) - 긴급

#### 1. ZIP 파일 문제
- [ ] S3 ZIP 파일 크기 확인 (`aws s3 ls`)
- [ ] ZIP 내용 확인 (`unzip -l`)
- [ ] Controller 업로드 로그에 파일 크기 로깅 추가
- [ ] 테스트용 올바른 ZIP 생성 및 업로드 (`test-manual`)
- [ ] 재테스트 실행

#### 2. Redis 연결 문제
- [ ] Controller EC2에서 `redis-cli ping` 테스트
- [ ] Redis Security Group 6379 포트 확인
- [ ] Controller VPC 확인 (`nanogrid-vpc`인지)
- [ ] Controller 로그에 Redis 구독/수신 로깅 추가
- [ ] 수동 Pub/Sub 테스트 (양쪽 터미널)

#### 3. Controller 코드 점검
- [ ] Redis 구독 로직 정상 동작 확인
- [ ] `status: "FAILED"` 메시지 처리 로직 확인
- [ ] 타임아웃 로직 점검 (25초 적절한지)

### C팀 (Worker) - 완료 ✅

- [x] 로그 분석 및 문제 발견
- [x] Docker Stats 에러 로그 정리 (DEBUG 레벨)
- [x] ZIP 비어있음 에러 메시지 강화
- [x] 코드 개선 및 빌드 완료
- [ ] GitHub Push (대기 중)
- [ ] EC2 최신 버전 배포 (대기 중)

---

## 🎯 예상 결과 (수정 후)

### 시나리오 1: 올바른 ZIP + Redis 정상

**Worker 로그:**
```
[INFO] Successfully downloaded zip file: 256 bytes
[INFO] Successfully extracted 1 files from zip
[INFO] Executing command: [python, main.py]
[INFO] Container exec finished with exitCode: 0
[INFO] ✅ [REDIS] Result published successfully, subscribers=1
[INFO] [DONE][OK] requestId=xxx
```

**Controller 응답:**
```json
{
  "requestId": "xxx",
  "functionId": "test-manual",
  "status": "SUCCESS",
  "exitCode": 0,
  "stdout": "Hello from NanoGrid Plus!\nTest function is working!\nResult: 42\n",
  "stderr": "",
  "durationMillis": 312,
  "peakMemoryMB": 2,
  "optimizationTip": "💡 Tip: ..."
}
```

### 시나리오 2: 빈 ZIP (현재 상태)

**Worker 로그 (업데이트 후):**
```
[INFO] Successfully downloaded zip file: 34 bytes
[INFO] Successfully extracted 0 files from zip
[ERROR] ❌ [S3][FAIL] ZIP 파일이 비어있습니다! requestId=xxx
[ERROR]    ⚠️ B팀에게 확인 요청: 함수 코드가 올바르게 업로드되었는지 확인 필요
[INFO] Container exec finished with exitCode: 2
[INFO] ✅ [REDIS] Result published successfully, subscribers=1 (또는 0)
```

**Controller가 받아야 할 메시지:**
```json
{
  "status": "FAILED",
  "exitCode": 2,
  "stderr": "python: can't open file 'main.py'..."
}
```

---

## 📞 필요한 정보 공유

다음 정보를 C팀에게 회신해주세요:

### 1. Redis 연결 테스트 결과
```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping
# 결과: _______________
```

### 2. Controller EC2 정보
```
- 인스턴스 ID: _______________
- VPC ID: _______________ (nanogrid-vpc인지 확인)
- Security Group: _______________
- Private IP: _______________
```

### 3. S3 ZIP 파일 확인
```bash
aws s3 ls s3://nanogrid-code-bucket/functions/1daed6ee-7da5-4b8d-a367-0098bc204d12/v1.zip --human-readable
# 크기: _______________ (현재 34 bytes)
```

### 4. Controller 로그 (특히 Redis 관련)
```
[시간] Subscribing to channel: result:xxx  ← 있는지?
[시간] Received message on channel: ...    ← 있는지?
[시간] Timeout waiting for result          ← 있는지?
```

### 5. 업로드 로그 (파일 크기)
```
[시간] Uploaded file size: ___ bytes
```

---

## 🚀 추천 해결 순서

### 1단계: 네트워크 확인 (5분)
```bash
# Controller EC2에서
redis-cli -h nanogrid-redis... ping
```
- ✅ PONG → 2단계로
- ❌ 실패 → Security Group 수정 후 재시도

### 2단계: 수동 Pub/Sub 테스트 (5분)
- Controller: SUBSCRIBE
- Worker: PUBLISH
- 메시지 수신 확인

### 3단계: 테스트 ZIP 생성 및 업로드 (10분)
```bash
# 올바른 ZIP 생성
cat > main.py <<'EOF'
print("Test OK")
EOF
zip test.zip main.py

# S3 업로드
aws s3 cp test.zip s3://nanogrid-code-bucket/functions/test-manual/v1.zip
```

### 4단계: Controller 코드 로깅 추가 (10분)
- 업로드 시 파일 크기 로깅
- Redis 구독/수신 로깅

### 5단계: End-to-End 재테스트 (5분)
```bash
curl -X POST http://43.202.0.218:8080/run \
  -H "Content-Type: application/json" \
  -d '{"functionId": "test-manual", "inputData": {}}'
```

---

## 💬 최종 요약

### 문제
1. 🚨 **ZIP 파일 비어있음** (34 bytes) → main.py 없음 → exitCode: 2
2. ⏱️ **Controller Redis 타임아웃** → Worker 결과를 못 받음

### 원인
- B팀 Controller의 **업로드 또는 Redis 연결 문제**
- Worker는 **100% 정상 동작 중**

### 해결
1. 올바른 ZIP 재업로드
2. Redis 연결 확인 및 Security Group 수정
3. Controller 로그 추가
4. 재테스트

### 예상 소요 시간
**30분 ~ 1시간** (조치 사항에 따라)

---

## ✅ Worker Agent 상태: 정상 ✅

```
✅ SQS 수신: 정상
✅ S3 다운로드: 정상
✅ Docker 실행: 정상 (312ms)
✅ 메모리 측정: 정상 (2.8MB)
✅ CloudWatch 전송: 정상
✅ Redis Publish: 정상 (1.3초 만에 완료)
```

**B팀의 조치만 완료되면 즉시 통합 완료됩니다!** 🚀

---

**문의 사항이나 추가 로그가 필요하면 언제든 연락 주세요!**

**C팀 (Worker Agent) 담당자**

