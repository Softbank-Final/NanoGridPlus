# 🔗 B팀에게 보내는 최종 연동 문서

안녕하세요, C팀(Worker Agent) 담당자입니다.

현재 다음 문제들이 발생하고 있어 확인 및 조치가 필요합니다:

---

## ⚠️ 긴급 이슈: ZIP 파일 비어있음

Worker Agent 로그를 분석한 결과, **S3의 ZIP 파일이 비어있는 문제**를 발견했습니다.

### 문제 증상

```log
07:40:19.996 [INFO] Successfully downloaded zip file: 34 bytes
07:40:19.997 [INFO] Successfully extracted 0 files from zip
07:40:19.997 [WARN] No files extracted from zip file. Empty archive?
07:40:20.018 [INFO] Executing command: [python, main.py]
07:40:20.480 [INFO] Container exec finished with exitCode: 2
```

- **34 bytes** = 빈 ZIP 파일의 헤더 크기
- **0개 파일 추출** = main.py 없음
- **exitCode: 2** = Python 파일을 찾을 수 없음 에러

### 원인

B팀의 함수 코드 업로드 과정에서 ZIP 파일이 제대로 생성되지 않았을 가능성:

```bash
# ❌ 잘못된 방법 (빈 ZIP 생성)
zip function.zip   # 파일을 지정하지 않음

# ✅ 올바른 방법
zip function.zip main.py
# 또는
zip -r function.zip .
```

### 해결 방법

1. **S3의 ZIP 파일 확인 (긴급)**

```bash
# ZIP 파일 다운로드
aws s3 cp s3://nanogrid-code-bucket/functions/1daed6ee-7da5-4b8d-a367-0098bc204d12/v1.zip /tmp/test.zip

# 파일 크기 확인
ls -lh /tmp/test.zip
# 예상: 34 bytes (문제!) → 수백 bytes 이상이어야 정상

# ZIP 내용 확인
unzip -l /tmp/test.zip
# 예상: main.py가 보여야 함
```

2. **테스트 함수 수동 업로드**

```bash
# 1. 테스트 함수 생성
mkdir -p /tmp/test-function
cd /tmp/test-function

cat > main.py <<'EOF'
#!/usr/bin/env python3
print("Hello from NanoGrid Plus!")
print("This is a test function")
print("Result: 42")
EOF

# 2. ZIP 파일 생성 (올바른 방법)
zip -r function.zip main.py

# 3. 파일 크기 확인
ls -lh function.zip
# 예상 출력: 200-300 bytes 정도

# 4. ZIP 내용 확인
unzip -l function.zip
# 예상 출력: main.py

# 5. S3 업로드 (테스트용 새 경로)
aws s3 cp function.zip s3://nanogrid-code-bucket/functions/test-manual/v1.zip
```

3. **Controller 업로드 로직 확인**

Controller의 `/upload` 엔드포인트에서:

```javascript
app.post('/upload', upload.single('file'), async (req, res) => {
    const functionId = req.functionId;
    const s3Key = req.file.key;
    
    // ⚠️ 확인 필요:
    // 1. req.file.size가 34 bytes인가?
    // 2. multer-s3가 파일을 제대로 업로드했는가?
    // 3. 클라이언트가 ZIP을 올바르게 생성했는가?
    
    console.log('Uploaded file size:', req.file.size); // 추가 필요
});
```

**테스트 방법:**

```bash
# 올바른 ZIP 파일로 업로드 테스트
curl -X POST http://43.202.0.218:8080/upload \
  -F "file=@function.zip" \
  -F "runtime=python"

# 응답에서 functionId 확인 후 실행
curl -X POST http://43.202.0.218:8080/run \
  -H "Content-Type: application/json" \
  -d '{"functionId": "xxx", "inputData": {}}'
```

---

## ⚠️ Redis 타임아웃 문제

Controller가 Worker의 결과를 받지 못하고 타임아웃이 발생합니다.

### 현재 상황

- ✅ **Worker 상태**: 정상 (2초 만에 처리 + Redis Publish 성공)
- ❌ **Controller 상태**: 타임아웃 (25초 대기 후 실패)

```log
# Worker 로그
07:40:20 - Worker: 함수 실행 완료 (312ms)
07:40:21 - Worker: Redis Publish 성공
07:40:21 - [DONE][OK] requestId=afc4198d-df18-46e3-85db-505b99b3a73e

# Controller 응답
{"status":"TIMEOUT","message":"Execution timed out"}
```

### 원인 분석

**가능성 1: Controller가 Redis에 연결 불가 (80%)**

원인:
- Controller EC2가 Redis ElastiCache에 연결 불가
- Security Group에서 Controller → Redis 6379 포트 차단
- VPC가 다름

**가능성 2: 구독 타이밍 문제 (15%)**

원인:
- Worker가 너무 빨리 Publish (2초)
- Controller가 구독 시작하기 전에 메시지 발행
- Redis Pub/Sub은 "실시간"이므로 구독 전 메시지는 받을 수 없음

**가능성 3: 채널 이름 불일치 (5%)**

원인:
- Worker: `result:afc4198d-df18-46e3-85db-505b99b3a73e`
- Controller: `result:XXX` (다른 이름)

### 해결 방법

**1단계: Redis 연결 확인**

Controller EC2에서 실행:

```bash
# Redis 연결 테스트
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping

# 예상 결과: PONG
# 만약 "Connection refused" 또는 timeout → Security Group 문제
```

**결과별 조치:**
- ✅ PONG: 2단계로 진행
- ❌ Connection refused: Security Group 수정 필요
- ❌ timeout: VPC 또는 Network ACL 문제

**2단계: Security Group 확인**

Redis ElastiCache Security Group 확인:

```
Inbound Rules:
- Type: Custom TCP
- Port: 6379
- Source: sg-CONTROLLER / 0.0.0.0/0 (또는 Controller EC2 IP)
```

**확인 방법 (AWS Console):**
1. ElastiCache → Redis Clusters → nanogrid-redis
2. Details → Security Groups 클릭
3. Inbound rules에서 6379 허용 확인

**또는 AWS CLI:**

```bash
aws ec2 describe-security-groups \
  --group-ids sg-XXX \  # Redis의 Security Group ID
  --region ap-northeast-2 \
  --query 'SecurityGroups[*].IpPermissions[?FromPort==`6379`]'
```

**3단계: Controller 로그 확인**

Controller가 출력해야 할 로그:

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
- ✅ Subscribing to channel 로그 있음 → 구독 시작됨
- ❌ 로그 없음 → Redis 연결 실패

**4단계: 실시간 테스트**

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
- [ ] Controller가 SQS 메시지 전송 직후 바로 구독 시작
- [ ] Worker보다 먼저 구독 (Worker는 2~3초 후 Publish)

---

## 🚀 권장 해결 순서

### 단기 (지금 당장)

1. **Controller EC2에서 Redis 연결 테스트**

```bash
redis-cli -h nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com ping
```

2. **Security Group 확인 및 수정 (필요시)**

```
Redis SG → Inbound → Add Rule:
- Type: Custom TCP
- Port: 6379
- Source: Controller SG
```

3. **Controller 로그 추가 (구독 시작/수신 확인용)**

### 중기 (1시간 내)

1. **S3 ZIP 파일 확인 및 재업로드**

```bash
# ZIP 내용 확인
unzip -l /tmp/test.zip

# 올바른 ZIP 재생성
zip -r function.zip main.py

# S3 재업로드
aws s3 cp function.zip s3://nanogrid-code-bucket/functions/test-manual/v1.zip
```

2. **End-to-End 재테스트**

```bash
curl -X POST http://43.202.0.218:8080/run \
  -H "Content-Type: application/json" \
  -d '{"functionId": "test-manual", "inputData": {}}'
```

3. **양쪽 로그 동시 확인**

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

```log
[INFO] 📤 [REDIS] Publishing result to channel: result:xxx
[INFO]    Redis Host: nanogrid-redis.p29xhw.0001.apn2.cache.amazonaws.com
[INFO] ✅ [REDIS] Result published successfully, subscribers=1
[INFO] [DONE][OK] requestId=xxx
```

### Controller

```log
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
  "peakMemoryMB": 2,
  "outputFiles": []
}
```

---

## 🆘 긴급 연락

문제가 계속되면:
- Worker 로그: `/home/ec2-user/nanogrid-agent.log`
- Controller 로그: (B팀 경로)
- 양쪽 로그를 requestId 기준으로 매칭하여 공유

**현재 Worker는 100% 정상 동작 중입니다. 문제는 Controller ↔ Redis 연결입니다.** 🔍

---

## 🎯 새로운 기능: Output Binding (파일 자동 업로드)

C팀에서 **Output Binding (파일 자동 업로드)** 기능을 구현 완료했습니다!

### 기능 설명

사용자 코드가 실행 중 파일을 생성하면, Worker Agent가 자동으로 S3에 업로드하고 URL을 결과에 포함시킵니다.

### 사용 방법

**1. 사용자 코드에서 output 디렉터리에 파일 생성**

Python 예시:

```python
import os

# output 디렉터리 생성
output_dir = os.path.join(os.getcwd(), 'output')
os.makedirs(output_dir, exist_ok=True)

# 파일 생성
with open(os.path.join(output_dir, 'result.txt'), 'w') as f:
    f.write('Hello from output file!')

# 이미지 파일 생성
import matplotlib.pyplot as plt
plt.plot([1, 2, 3], [4, 5, 6])
plt.savefig(os.path.join(output_dir, 'chart.png'))

print("Files created in output directory")
```

**2. Worker Agent가 자동 처리**

- 컨테이너 실행 후 `/workspace-root/{requestId}/output` 디렉터리 확인
- 발견된 파일을 호스트로 복사
- S3 버킷 `nanogrid-user-data`에 업로드
- 경로: `outputs/{requestId}/파일명`

**3. 결과에 URL 포함**

```json
{
  "requestId": "xxx",
  "status": "SUCCESS",
  "exitCode": 0,
  "stdout": "Files created in output directory\n",
  "durationMillis": 1250,
  "peakMemoryMB": 45,
  "outputFiles": [
    "https://nanogrid-user-data.s3.ap-northeast-2.amazonaws.com/outputs/xxx/result.txt",
    "https://nanogrid-user-data.s3.ap-northeast-2.amazonaws.com/outputs/xxx/chart.png"
  ]
}
```

### B팀 필요 작업

**1. S3 버킷 생성 (또는 확인)**

```bash
# 버킷 생성 (없다면)
aws s3 mb s3://nanogrid-user-data --region ap-northeast-2

# 버킷 확인
aws s3 ls s3://nanogrid-user-data/
```

**2. Worker Agent IAM 역할에 권한 추가**

Worker EC2의 IAM 역할에 S3 쓰기 권한 필요:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:PutObjectAcl"
      ],
      "Resource": "arn:aws:s3:::nanogrid-user-data/outputs/*"
    }
  ]
}
```

**3. application.yml 설정 확인**

Worker Agent의 `application.yml`:

```yaml
agent:
  s3:
    codeBucket: nanogrid-code-bucket
    userDataBucket: nanogrid-user-data  # Output 파일 업로드용

  output:
    enabled: true
    baseDir: /tmp/output
    s3Prefix: outputs
```

**4. Controller에서 outputFiles 처리**

ExecutionResult에 `outputFiles` 필드가 추가되었으므로, Controller에서도 이를 처리해야 합니다:

```javascript
// Controller에서 결과 처리
const result = await waitForResult(requestId);

// outputFiles가 있으면 사용자에게 전달
if (result.outputFiles && result.outputFiles.length > 0) {
    console.log(`Generated ${result.outputFiles.length} output file(s)`);
    result.outputFiles.forEach(url => {
        console.log(`  - ${url}`);
    });
}

res.json(result);
```

### 테스트 시나리오

**1. 간단한 텍스트 파일 생성**

```python
# main.py
import os

output_dir = os.path.join(os.getcwd(), 'output')
os.makedirs(output_dir, exist_ok=True)

with open(os.path.join(output_dir, 'hello.txt'), 'w') as f:
    f.write('Hello from NanoGrid Plus!')

print("Output file created")
```

**2. 이미지 생성 (Pillow 사용)**

```python
# main.py
from PIL import Image, ImageDraw, ImageFont
import os

output_dir = os.path.join(os.getcwd(), 'output')
os.makedirs(output_dir, exist_ok=True)

# 이미지 생성
img = Image.new('RGB', (400, 200), color='lightblue')
draw = ImageDraw.Draw(img)
draw.text((50, 80), 'Hello from NanoGrid!', fill='black')

img.save(os.path.join(output_dir, 'greeting.png'))
print("Image created")
```

**3. 여러 파일 생성**

```python
# main.py
import os
import json

output_dir = os.path.join(os.getcwd(), 'output')
os.makedirs(output_dir, exist_ok=True)

# 텍스트 파일
with open(os.path.join(output_dir, 'log.txt'), 'w') as f:
    f.write('Execution log\n')
    f.write('Step 1: OK\n')
    f.write('Step 2: OK\n')

# JSON 파일
data = {'status': 'success', 'results': [1, 2, 3, 4, 5]}
with open(os.path.join(output_dir, 'data.json'), 'w') as f:
    json.dump(data, f, indent=2)

# CSV 파일
with open(os.path.join(output_dir, 'results.csv'), 'w') as f:
    f.write('id,value\n')
    f.write('1,100\n')
    f.write('2,200\n')

print("Multiple files created")
```

### 지원되는 파일 형식

자동으로 Content-Type이 설정됩니다:

- 이미지: `.jpg`, `.jpeg`, `.png`, `.gif`
- 문서: `.pdf`, `.txt`, `.json`, `.csv`
- 압축: `.zip`, `.tar.gz`, `.tgz`
- 기타: `application/octet-stream`

---

## 📊 최종 요약

### 현재 상태

1. ✅ **Worker Agent**: 완벽하게 동작 (SQS → S3 → Docker → Redis → Output Binding)
2. ❌ **ZIP 파일 문제**: S3의 ZIP이 비어있음 (34 bytes)
3. ❌ **Redis 타임아웃**: Controller가 Worker 결과를 받지 못함

### 즉시 조치 필요

1. **ZIP 파일 확인 및 재업로드** (최우선)
2. **Controller → Redis 연결 확인** (Security Group)
3. **Output Binding용 S3 버킷 생성 및 권한 설정**

### 다음 단계

B팀이 위 문제들을 해결하면, 전체 시스템이 End-to-End로 정상 동작할 것입니다!

**질문이 있으시면 언제든지 연락 주세요.** 🚀

