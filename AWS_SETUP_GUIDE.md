# NanoGrid Plus - AWS 리소스 생성 가이드

## 📋 필요한 AWS 리소스

NanoGrid Plus Agent를 실행하려면 다음 AWS 리소스가 필요합니다:

1. **SQS Queue** - 작업 메시지 수신
2. **S3 Bucket** - 함수 코드 저장
3. **IAM Role** - EC2 권한 설정
4. **CloudWatch** - 메트릭 저장 (자동 생성)

---

## 🛠️ 1. SQS 큐 생성

### AWS 콘솔 방법

#### 1.1) SQS 서비스 접속
```
AWS Console → Services → SQS 검색 → Simple Queue Service
```

#### 1.2) 큐 생성
```
1. "Create queue" 버튼 클릭

2. Type 선택:
   ○ Standard Queue (선택) ✅
   ○ FIFO Queue

3. Name:
   nanogrid-task-queue

4. Configuration:
   - Visibility timeout: 300 seconds
   - Message retention period: 14 days
   - Receive message wait time: 20 seconds ⭐ (Long Polling)
   - Maximum message size: 256 KB

5. Dead-letter queue (선택):
   ✅ Enabled
   - Queue: nanogrid-task-queue-dlq (새로 생성)
   - Maximum receives: 3

6. "Create queue" 클릭
```

#### 1.3) 큐 URL 복사
```
1. 생성된 큐 선택
2. "Details" 탭
3. URL 복사:
   예: https://sqs.ap-northeast-2.amazonaws.com/123456789012/nanogrid-task-queue
```

### AWS CLI 방법

```bash
# 메인 큐 생성
aws sqs create-queue \
  --queue-name nanogrid-task-queue \
  --region ap-northeast-2 \
  --attributes '{
    "VisibilityTimeout": "300",
    "MessageRetentionPeriod": "1209600",
    "ReceiveMessageWaitTimeSeconds": "20"
  }'

# DLQ 생성 (실패한 메시지 저장)
aws sqs create-queue \
  --queue-name nanogrid-task-queue-dlq \
  --region ap-northeast-2

# 큐 URL 확인
aws sqs get-queue-url \
  --queue-name nanogrid-task-queue \
  --region ap-northeast-2
```

---

## 🗄️ 2. S3 버킷 생성

### AWS 콘솔 방법

#### 2.1) S3 서비스 접속
```
AWS Console → Services → S3 검색 → S3
```

#### 2.2) 버킷 생성
```
1. "Create bucket" 버튼 클릭

2. General configuration:
   - Bucket name: nanogrid-code-bucket
     (전역 고유해야 함. 예: nanogrid-code-bucket-20251130)
   - AWS Region: ap-northeast-2 (Asia Pacific Seoul)

3. Object Ownership:
   - ACLs disabled (선택)

4. Block Public Access:
   ✅ Block all public access (체크 유지)

5. Bucket Versioning:
   ○ Enable (선택)
   ○ Disable

6. Encryption:
   - Server-side encryption: SSE-S3 (선택)

7. "Create bucket" 클릭
```

#### 2.3) 버킷 구조 설정 (선택)
```
버킷 생성 후 폴더 구조:

nanogrid-code-bucket/
├── functions/
│   ├── hello-python/
│   │   └── v1.zip
│   ├── calc-cpp/
│   │   └── v1.zip
│   └── ...
└── ...
```

### AWS CLI 방법

```bash
# 버킷 생성
aws s3 mb s3://nanogrid-code-bucket --region ap-northeast-2

# 버킷 확인
aws s3 ls

# 암호화 활성화
aws s3api put-bucket-encryption \
  --bucket nanogrid-code-bucket \
  --server-side-encryption-configuration '{
    "Rules": [{
      "ApplyServerSideEncryptionByDefault": {
        "SSEAlgorithm": "AES256"
      }
    }]
  }'
```

---

## 🔐 3. IAM Role 생성 (EC2용)

### AWS 콘솔 방법

#### 3.1) IAM 서비스 접속
```
AWS Console → Services → IAM 검색 → IAM
```

#### 3.2) Role 생성
```
1. 좌측 메뉴 → "Roles" → "Create role"

2. Trusted entity type:
   ○ AWS service (선택)
   - Use case: EC2 ✅

3. Permissions policies:
   다음 정책 선택 또는 인라인 정책 생성:
   
   ✅ AmazonSQSFullAccess (또는 아래 커스텀 정책)
   ✅ AmazonS3ReadOnlyAccess (또는 아래 커스텀 정책)
   ✅ CloudWatchAgentServerPolicy

4. Role name:
   NanoGridAgentRole

5. "Create role" 클릭
```

#### 3.3) 커스텀 정책 (최소 권한)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SQSAccess",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:ap-northeast-2:123456789012:nanogrid-task-queue"
    },
    {
      "Sid": "S3Access",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::nanogrid-code-bucket",
        "arn:aws:s3:::nanogrid-code-bucket/*"
      ]
    },
    {
      "Sid": "CloudWatchAccess",
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData"
      ],
      "Resource": "*"
    }
  ]
}
```

### AWS CLI 방법

```bash
# Trust policy 파일 생성
cat > trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"Service": "ec2.amazonaws.com"},
    "Action": "sts:AssumeRole"
  }]
}
EOF

# Role 생성
aws iam create-role \
  --role-name NanoGridAgentRole \
  --assume-role-policy-document file://trust-policy.json

# 정책 연결
aws iam attach-role-policy \
  --role-name NanoGridAgentRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSQSFullAccess

aws iam attach-role-policy \
  --role-name NanoGridAgentRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess

aws iam attach-role-policy \
  --role-name NanoGridAgentRole \
  --policy-arn arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy

# Instance Profile 생성 (EC2용)
aws iam create-instance-profile \
  --instance-profile-name NanoGridAgentProfile

aws iam add-role-to-instance-profile \
  --instance-profile-name NanoGridAgentProfile \
  --role-name NanoGridAgentRole
```

---

## 📝 4. application.yml 설정

### 4.1) AWS 계정 ID 확인

```bash
# AWS CLI로 확인
aws sts get-caller-identity --query Account --output text

# 또는 AWS Console 우측 상단 클릭 → Account ID 확인
```

### 4.2) application.yml 업데이트

```yaml
agent:
  # AWS 설정
  aws:
    region: ap-northeast-2  # Seoul 리전
  
  # SQS 설정
  sqs:
    queueUrl: https://sqs.ap-northeast-2.amazonaws.com/YOUR_ACCOUNT_ID/nanogrid-task-queue
    # ↑ YOUR_ACCOUNT_ID를 실제 AWS 계정 ID로 변경
    # 예: 123456789012
    waitTimeSeconds: 20
    maxNumberOfMessages: 10
  
  # S3 설정
  s3:
    codeBucket: nanogrid-code-bucket
    # ↑ 생성한 버킷 이름으로 변경
```

---

## 🧪 5. 테스트 메시지 전송

### 5.1) S3에 테스트 코드 업로드

#### Python 예시 (main.py)
```python
#!/usr/bin/env python3
print("Hello from NanoGrid!")
print("Result: 42")
```

#### zip으로 압축
```bash
mkdir test-hello-python
cd test-hello-python
cat > main.py <<EOF
#!/usr/bin/env python3
print("Hello from NanoGrid!")
print("Result: 42")
EOF

zip ../hello-python.zip main.py
cd ..
```

#### S3 업로드
```bash
aws s3 cp hello-python.zip s3://nanogrid-code-bucket/functions/hello-python/v1.zip
```

### 5.2) SQS 메시지 전송

```bash
# 메시지 JSON 작성
cat > test-message.json <<EOF
{
  "requestId": "test-001",
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
  --message-body file://test-message.json \
  --region ap-northeast-2
```

---

## 📊 6. CloudWatch 확인

Agent가 실행되면 자동으로 CloudWatch 메트릭이 생성됩니다:

```
1. AWS Console → CloudWatch
2. 좌측 메뉴 → "Metrics" → "All metrics"
3. "Custom namespaces" → "NanoGrid/FunctionRunner"
4. "PeakMemoryBytes" 메트릭 확인
5. Dimensions:
   - FunctionId: hello-python
   - Runtime: python
```

---

## ✅ 완료 체크리스트

- [ ] SQS 큐 생성 완료
  - [ ] 메인 큐: nanogrid-task-queue
  - [ ] DLQ: nanogrid-task-queue-dlq
  - [ ] Long Polling 설정 (20초)
  
- [ ] S3 버킷 생성 완료
  - [ ] 버킷 이름 확정 (전역 고유)
  - [ ] 암호화 활성화
  - [ ] 테스트 코드 업로드
  
- [ ] IAM Role 생성 완료
  - [ ] EC2 Trust relationship
  - [ ] SQS/S3/CloudWatch 권한
  - [ ] Instance Profile 생성
  
- [ ] application.yml 설정 완료
  - [ ] 실제 Queue URL 입력
  - [ ] 실제 Bucket 이름 입력
  - [ ] Region 확인
  
- [ ] 테스트 메시지 전송
  - [ ] S3에 코드 업로드
  - [ ] SQS 메시지 전송
  - [ ] Agent 로그 확인

---

## 💰 비용 예상

### 프리티어 (첫 12개월)
- **SQS**: 100만 요청/월 무료
- **S3**: 5GB 스토리지 + 20,000 GET 요청 무료
- **CloudWatch**: 10개 메트릭 무료

### 프리티어 이후 (서울 리전)
- **SQS**: $0.40 / 100만 요청
- **S3**: $0.025 / GB·월
- **CloudWatch**: $0.30 / 메트릭·월
- **데이터 전송**: 1GB까지 무료

**예상 월 비용**: 테스트 수준에서는 거의 무료 ($1 미만)

---

## 🔧 문제 해결

### SQS Queue URL이 작동하지 않음
```
원인: Region 불일치 또는 계정 ID 오류
해결:
1. AWS Console → SQS → 큐 선택 → Details → URL 확인
2. application.yml의 region과 일치하는지 확인
```

### S3 Access Denied
```
원인: IAM Role 권한 부족
해결:
1. EC2 인스턴스에 IAM Role이 연결되어 있는지 확인
2. Role에 s3:GetObject 권한이 있는지 확인
```

### CloudWatch 메트릭이 안 보임
```
원인: cloudwatch:PutMetricData 권한 부족
해결:
1. IAM Role에 CloudWatch 권한 추가
2. Agent 로그에서 "Successfully published" 확인
```

---

**작성일**: 2025-11-30  
**버전**: 1.0  
**다음 단계**: [EC2 배포 가이드](./EC2_DEPLOYMENT_GUIDE.md)

