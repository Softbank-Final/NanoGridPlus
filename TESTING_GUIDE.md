# 🧪 NanoGrid Plus Agent - 완전한 테스트 가이드

## 📋 테스트 개요

이 가이드는 EC2에서 NanoGrid Plus Agent를 완전히 테스트하는 방법을 설명합니다.

---

## 🎯 테스트 시나리오

### 1️⃣ Agent 빌드 및 실행 (현재 단계)
### 2️⃣ Health Check 테스트
### 3️⃣ Warm Pool 확인
### 4️⃣ SQS 메시지 전송 및 실행 테스트
### 5️⃣ CloudWatch 메트릭 확인

---

## 1️⃣ Agent 빌드 및 실행

### EC2에서 빌드 완료 확인

```bash
cd NanoGridPlus

# 빌드 (이미 성공했다면 생략)
./gradlew clean bootJar

# JAR 파일 확인
ls -lh build/libs/
# -rw-r--r-- 1 ec2-user 45M NanoGridPlus-0.0.1-SNAPSHOT.jar
```

### Agent 실행

```bash
# JAVA_HOME 설정 확인
export JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-amazon-corretto* | head -1)
export PATH=$JAVA_HOME/bin:$PATH

# 백그라운드 실행
nohup java -jar build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=prod \
    > app.log 2>&1 &

# PID 저장
echo $! > agent.pid

# PID 확인
cat agent.pid
```

### 로그 확인

```bash
# 실시간 로그 확인
tail -f app.log

# 또는 최근 100줄
tail -100 app.log

# 초기 시작 로그 확인
head -50 app.log
```

**예상 로그**:
```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::       (v4.0.0)

2025-11-30 10:00:00.123 INFO  NanoGridPlusApplication : Starting NanoGridPlusApplication
2025-11-30 10:00:01.234 INFO  DockerWarmPoolManager : ========================================
2025-11-30 10:00:01.235 INFO  DockerWarmPoolManager : Initializing Warm Pool Manager
2025-11-30 10:00:01.236 INFO  DockerWarmPoolManager : Creating 2 Python containers for Warm Pool
2025-11-30 10:00:05.123 INFO  DockerWarmPoolManager : [1] Python container created: abc123...
2025-11-30 10:00:08.234 INFO  DockerWarmPoolManager : [2] Python container created: def456...
2025-11-30 10:00:08.235 INFO  DockerWarmPoolManager : Creating 1 C++ containers for Warm Pool
2025-11-30 10:00:11.345 INFO  DockerWarmPoolManager : [1] C++ container created: ghi789...
2025-11-30 10:00:11.346 INFO  NanoGridPlusApplication : Started NanoGridPlusApplication in 11.2 seconds
```

---

## 2️⃣ Health Check 테스트

### 새 터미널 열기 (또는 Ctrl+C로 tail 종료 후)

```bash
# Health Check
curl http://localhost:8080/health
```

**예상 응답**:
```
OK
```

### Status Check

```bash
# Status Check (JSON)
curl http://localhost:8080/status | python3 -m json.tool
```

**예상 응답**:
```json
{
  "status": "UP",
  "application": "NanoGridPlus Agent",
  "region": "ap-northeast-2",
  "warmPool": {
    "enabled": true,
    "pythonSize": 2,
    "cppSize": 1
  },
  "sqs": {
    "enabled": true,
    "queueUrl": "https://sqs.../***"
  },
  "docker": {
    "pythonImage": "python-base",
    "cppImage": "gcc-base"
  }
}
```

---

## 3️⃣ Warm Pool 확인

### Docker 컨테이너 확인

```bash
# Warm Pool 컨테이너 목록
docker ps -a | grep nanogrid-warmpool

# 예상 출력:
# abc123... python-base "sleep infinity" ... Paused  nanogrid-warmpool-python-...
# def456... python-base "sleep infinity" ... Paused  nanogrid-warmpool-python-...
# ghi789... gcc-base    "sleep infinity" ... Paused  nanogrid-warmpool-cpp-...
```

### 컨테이너 상태 확인

```bash
# 특정 컨테이너 상세 정보
docker inspect $(docker ps -aq --filter name=nanogrid-warmpool-python | head -1) | grep -A 5 State
```

**예상 출력**:
```json
"State": {
    "Status": "paused",
    "Running": true,
    "Paused": true,
    ...
}
```

---

## 4️⃣ SQS 메시지 전송 및 실행 테스트

### 준비: S3에 테스트 코드 업로드

#### Python 테스트 코드 생성

```bash
# 로컬 PC에서 (또는 EC2에서)
mkdir -p test-functions/hello-python
cd test-functions/hello-python

# main.py 생성
cat > main.py <<'EOF'
#!/usr/bin/env python3
print("Hello from NanoGrid Plus!")
print("Agent is working perfectly!")
print("Result: 42")
EOF

# zip으로 압축
zip hello-python.zip main.py

# S3 업로드
aws s3 cp hello-python.zip s3://nanogrid-code-bucket/functions/hello-python/v1.zip

# 확인
aws s3 ls s3://nanogrid-code-bucket/functions/hello-python/
```

#### C++ 테스트 코드 생성 (선택)

```bash
mkdir -p test-functions/hello-cpp
cd test-functions/hello-cpp

# main.cpp 생성
cat > main.cpp <<'EOF'
#include <iostream>
int main() {
    std::cout << "Hello from C++ NanoGrid!" << std::endl;
    std::cout << "Result: 100" << std::endl;
    return 0;
}
EOF

# run.sh 생성
cat > run.sh <<'EOF'
#!/bin/bash
g++ -o hello main.cpp
./hello
EOF
chmod +x run.sh

# zip으로 압축
zip hello-cpp.zip main.cpp run.sh

# S3 업로드
aws s3 cp hello-cpp.zip s3://nanogrid-code-bucket/functions/hello-cpp/v1.zip
```

### SQS 메시지 전송

#### Python 함수 테스트

```bash
# 메시지 JSON 생성
cat > test-message-python.json <<'EOF'
{
  "requestId": "test-req-001",
  "functionId": "hello-python",
  "runtime": "python",
  "s3Bucket": "nanogrid-code-bucket",
  "s3Key": "functions/hello-python/v1.zip",
  "timeoutMs": 5000,
  "memoryMb": 256
}
EOF

# SQS로 전송
aws sqs send-message \
  --queue-url https://sqs.ap-northeast-2.amazonaws.com/YOUR_ACCOUNT_ID/nanogrid-task-queue \
  --message-body file://test-message-python.json \
  --region ap-northeast-2

# 응답 예시:
# {
#     "MD5OfMessageBody": "...",
#     "MessageId": "..."
# }
```

### Agent 로그 확인

```bash
# EC2에서 실시간 로그 확인
tail -f app.log
```

**예상 로그** (성공 시):
```
INFO  SqsPoller : ===== 작업 메시지 수신 =====
INFO  SqsPoller : Received task: TaskMessage[requestId=test-req-001, functionId=hello-python, ...]
INFO  SqsPoller :   - Request ID: test-req-001
INFO  SqsPoller :   - Function ID: hello-python
INFO  SqsPoller :   - Runtime: python
INFO  S3CodeStorageService : Preparing working directory for request: test-req-001
INFO  S3CodeStorageService : Downloading from S3: s3://nanogrid-code-bucket/functions/hello-python/v1.zip
INFO  S3CodeStorageService : Successfully prepared working directory: /tmp/task/test-req-001
INFO  DockerEngineService : Starting Warm Pool execution for request: test-req-001, runtime: python
INFO  DockerWarmPoolManager : Acquired and unpaused container: abc123...
INFO  DockerEngineService : Executing command in container abc123...: [python, main.py]
INFO  DockerStatsResourceMonitor : Measured peak memory for container abc123...: 67108864 bytes (64 MB)
INFO  CloudWatchMetricsPublisher : Publishing peak memory metric to CloudWatch
INFO  CloudWatchMetricsPublisher : Successfully published peak memory metric to CloudWatch
INFO  AutoTunerService : Auto-Tuner analysis: functionId=hello-python, allocatedMb=256, peakMemoryBytes=67108864, ratio=0.25
INFO  AutoTunerService : Generated optimization tip: 💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(64MB)이 매우 낮습니다. 메모리를 96MB 정도로 줄이면 비용을 약 62% 절감할 수 있습니다.
INFO  DockerEngineService : Container abc123... exec finished with exitCode: 0 in 1234ms
INFO  SqsPoller : ===== 실행 결과 =====
INFO  SqsPoller : Request: test-req-001 finished in 2345ms
INFO  SqsPoller :   - Exit Code: 0
INFO  SqsPoller :   - Duration: 1234ms
INFO  SqsPoller :   - Peak Memory: 67108864 bytes
INFO  SqsPoller :   - Success: true
INFO  SqsPoller :   - Optimization Tip: 💡 Tip: 현재 메모리 설정(256MB)에 비해 실제 사용량(64MB)이 매우 낮습니다...
INFO  SqsPoller : ============================
DEBUG SqsPoller : Stdout:
Hello from NanoGrid Plus!
Agent is working perfectly!
Result: 42
INFO  SqsPoller : [DONE][OK] requestId=test-req-001
```

### stdout 확인

```bash
# 로그에서 특정 requestId의 stdout만 추출
grep -A 10 "test-req-001" app.log | grep -A 5 "Stdout"
```

---

## 5️⃣ CloudWatch 메트릭 확인

### AWS Console에서 확인

```
1. AWS Console → CloudWatch
2. 좌측 메뉴 → "Metrics" → "All metrics"
3. "Custom namespaces" → "NanoGrid/FunctionRunner"
4. "PeakMemoryBytes" 선택
5. Dimensions:
   - FunctionId: hello-python
   - Runtime: python
```

### AWS CLI로 확인

```bash
# 최근 1시간 메트릭 조회
aws cloudwatch get-metric-statistics \
  --namespace NanoGrid/FunctionRunner \
  --metric-name PeakMemoryBytes \
  --dimensions Name=FunctionId,Value=hello-python Name=Runtime,Value=python \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Average \
  --region ap-northeast-2
```

---

## 🧪 추가 테스트 시나리오

### 테스트 1: 여러 메시지 동시 전송

```bash
# 5개 메시지 전송
for i in {1..5}; do
  cat > test-msg-$i.json <<EOF
{
  "requestId": "test-req-00$i",
  "functionId": "hello-python",
  "runtime": "python",
  "s3Bucket": "nanogrid-code-bucket",
  "s3Key": "functions/hello-python/v1.zip",
  "timeoutMs": 5000,
  "memoryMb": 256
}
EOF
  aws sqs send-message \
    --queue-url YOUR_QUEUE_URL \
    --message-body file://test-msg-$i.json \
    --region ap-northeast-2
  echo "Sent message $i"
done

# 로그 확인
tail -f app.log
```

### 테스트 2: C++ 함수 테스트

```bash
cat > test-message-cpp.json <<'EOF'
{
  "requestId": "test-req-cpp-001",
  "functionId": "hello-cpp",
  "runtime": "cpp",
  "s3Bucket": "nanogrid-code-bucket",
  "s3Key": "functions/hello-cpp/v1.zip",
  "timeoutMs": 10000,
  "memoryMb": 512
}
EOF

aws sqs send-message \
  --queue-url YOUR_QUEUE_URL \
  --message-body file://test-message-cpp.json \
  --region ap-northeast-2
```

### 테스트 3: 실패 케이스 (S3 파일 없음)

```bash
cat > test-message-fail.json <<'EOF'
{
  "requestId": "test-req-fail-001",
  "functionId": "not-exist",
  "runtime": "python",
  "s3Bucket": "nanogrid-code-bucket",
  "s3Key": "functions/not-exist/v1.zip",
  "timeoutMs": 5000,
  "memoryMb": 128
}
EOF

aws sqs send-message \
  --queue-url YOUR_QUEUE_URL \
  --message-body file://test-message-fail.json \
  --region ap-northeast-2
```

**예상 로그**:
```
ERROR SqsPoller : [FAIL][S3] 실행 중 오류 발생: requestId=test-req-fail-001
(메시지 삭제하지 않음 - SQS에서 재시도)
```

---

## 📊 Agent 모니터링

### 시스템 리소스

```bash
# CPU/메모리 사용량
top -p $(cat agent.pid)

# Docker 리소스
docker stats $(docker ps -aq --filter name=nanogrid-warmpool)
```

### Agent 상태

```bash
# 프로세스 확인
ps aux | grep java

# 로그 요약
tail -100 app.log | grep -E "(ERROR|WARN|DONE)"

# 성공/실패 통계
grep "\[DONE\]\[OK\]" app.log | wc -l  # 성공 수
grep "\[FAIL\]" app.log | wc -l        # 실패 수
```

---

## 🛑 Agent 중지

```bash
# 정상 종료
kill $(cat agent.pid)

# 강제 종료 (필요시)
kill -9 $(cat agent.pid)

# PID 파일 삭제
rm agent.pid

# 로그 확인
tail -20 app.log
```

---

## ✅ 테스트 체크리스트

### 기본 테스트
- [ ] Agent 빌드 성공
- [ ] Agent 시작 성공
- [ ] Warm Pool 초기화 (Python 2개, C++ 1개)
- [ ] Health Check 응답
- [ ] Status API 응답

### 기능 테스트
- [ ] S3에 테스트 코드 업로드
- [ ] SQS 메시지 전송
- [ ] 메시지 수신 확인 (로그)
- [ ] S3 다운로드 성공
- [ ] Docker 실행 성공
- [ ] stdout 출력 확인
- [ ] Auto-Tuner 팁 생성
- [ ] CloudWatch 메트릭 전송
- [ ] SQS 메시지 삭제

### 성능 테스트
- [ ] Cold Start 시간 측정 (첫 실행)
- [ ] Warm Start 시간 측정 (두 번째 실행)
- [ ] 동시 요청 처리 (5개 이상)
- [ ] Warm Pool 컨테이너 재사용 확인

### 에러 처리 테스트
- [ ] 존재하지 않는 S3 파일 → 재시도
- [ ] 잘못된 런타임 → 재시도
- [ ] 컨테이너 내부 오류 → 성공 처리 (exitCode != 0)

---

## 🎉 테스트 성공 기준

### ✅ 모든 테스트 통과 시:

```
✅ Agent 정상 시작
✅ Warm Pool 초기화 완료
✅ Health Check 응답
✅ SQS 메시지 처리
✅ S3 다운로드 성공
✅ Docker 실행 성공 (exitCode=0)
✅ stdout 출력 정상
✅ Auto-Tuner 팁 생성
✅ CloudWatch 메트릭 전송
✅ 성능 요구사항 충족 (Cold Start < 5초)
```

**축하합니다! NanoGrid Plus Agent가 완벽하게 작동합니다!** 🎊🚀

---

## 📚 참고 문서

- `AWS_SETUP_GUIDE.md` - AWS 리소스 생성
- `EC2_DEPLOYMENT.md` - EC2 배포 가이드
- `STAGE7_8_REPORT.md` - 7~8단계 안정화
- `STAGE5_REPORT.md` - Auto-Tuner 구현

---

**작성일**: 2025-11-30  
**버전**: 1.0  
**테스트 준비 완료!** ✅

