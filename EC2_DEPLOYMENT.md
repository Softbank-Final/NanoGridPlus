# NanoGrid Plus - EC2 배포 가이드

## 📋 전체 배포 프로세스

```
로컬 개발 → GitHub Push → EC2 클론 → 빌드 & 실행
```

---

## 🚀 빠른 시작 (EC2 배포)

### 1단계: 로컬에서 GitHub에 Push

```bash
# 로컬 PC에서
cd NanoGridPlus

# Git 초기화 (처음 한 번만)
git init
git add .
git commit -m "Initial commit"

# GitHub 저장소 연결
git remote add origin https://github.com/YOUR_USERNAME/NanoGridPlus.git
git branch -M main
git push -u origin main
```

### 2단계: EC2 인스턴스 생성

```
1. AWS Console → EC2 → Launch Instance
2. 설정:
   - AMI: Amazon Linux 2023
   - Instance Type: t3.medium (최소)
   - Key Pair: 생성 또는 선택
   - Security Group:
     - 22 (SSH): Your IP
     - 8080 (HTTP): Anywhere (HealthCheck용)
   - IAM Role: NanoGridAgentRole
3. Launch
```

### 3단계: EC2 초기 설정

```bash
# SSH 접속
ssh -i your-key.pem ec2-user@YOUR_EC2_IP

# 초기 설정 스크립트 실행
curl -O https://raw.githubusercontent.com/YOUR_REPO/NanoGridPlus/main/setup-ec2.sh
chmod +x setup-ec2.sh
./setup-ec2.sh

# 로그아웃 후 재접속 (Docker 그룹 적용)
exit
ssh -i your-key.pem ec2-user@YOUR_EC2_IP
```

### 4단계: 프로젝트 클론 및 배포

```bash
# 프로젝트 클론
git clone https://github.com/YOUR_USERNAME/NanoGridPlus.git
cd NanoGridPlus

# 배포 스크립트 실행
chmod +x deploy-ec2.sh
./deploy-ec2.sh
```

### 5단계: 확인

```bash
# Health Check
curl http://localhost:8080/health

# Status Check
curl http://localhost:8080/status

# 로그 확인
tail -f /home/ec2-user/nanogrid-agent.log

# 또는 프로젝트 루트의 로그
tail -f nanogrid-agent.log
```

---

## 🔄 코드 업데이트 방법

로컬에서 코드를 수정한 후:

```bash
# 로컬 PC에서
git add .
git commit -m "Update feature"
git push origin main

# EC2에서
cd NanoGridPlus
./deploy-ec2.sh  # 자동으로 pull + 빌드 + 재시작
```

---

## 📊 운영 명령어

### Agent 상태 확인
```bash
# PID 확인
cat agent.pid

# 프로세스 확인
ps aux | grep java

# 로그 실시간 확인
tail -f /home/ec2-user/nanogrid-agent.log

# 최근 100줄
tail -100 /home/ec2-user/nanogrid-agent.log

# 에러만 확인
grep ERROR /home/ec2-user/nanogrid-agent.log
```

### Agent 중지
```bash
# 정상 종료
kill $(cat agent.pid)

# 강제 종료
kill -9 $(cat agent.pid)
```

### Agent 재시작
```bash
# 방법 1: 배포 스크립트 사용 (권장)
./deploy-ec2.sh

# 방법 2: 수동 재시작
kill $(cat agent.pid)
nohup java -jar build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=prod \
    > /home/ec2-user/nanogrid-agent.log 2>&1 &
echo $! > agent.pid
```

### Docker 상태 확인
```bash
# 실행 중인 컨테이너
docker ps

# Warm Pool 컨테이너 확인
docker ps -a | grep nanogrid-warmpool

# 이미지 확인
docker images | grep -E "python-base|gcc-base"
```

---

## 🐛 문제 해결

### 1. Agent가 시작되지 않음
```bash
# 로그 확인
tail -50 /home/ec2-user/nanogrid-agent.log

# Java 설치 확인
java -version

# Docker 실행 확인
docker ps
```

### 2. SQS 연결 실패
```bash
# IAM Role 확인
aws sts get-caller-identity

# 네트워크 확인
curl https://sqs.ap-northeast-2.amazonaws.com/

# application-prod.yml 확인
cat src/main/resources/application-prod.yml
```

### 3. Docker 이미지 없음
```bash
# 이미지 재생성
docker pull python:3.9-slim
docker tag python:3.9-slim python-base

docker pull gcc:11
docker tag gcc:11 gcc-base
```

### 4. 포트 8080 사용 중
```bash
# 포트 사용 확인
sudo lsof -i :8080

# 프로세스 종료
sudo kill -9 $(sudo lsof -t -i:8080)
```

---

## 📁 파일 구조 (EC2)

```
/home/ec2-user/
├── NanoGridPlus/                    # 프로젝트 루트
│   ├── src/
│   ├── build/
│   │   └── libs/
│   │       └── NanoGridPlus-0.0.1-SNAPSHOT.jar
│   ├── deploy-ec2.sh                # 배포 스크립트
│   ├── agent.pid                    # PID 파일
│   └── ...
├── nanogrid-agent.log               # 애플리케이션 로그
└── setup-ec2.sh                     # 초기 설정 스크립트

/tmp/task/                           # 작업 디렉터리
└── {requestId}/                     # 요청별 디렉터리
    ├── main.py
    └── ...
```

---

## 🔐 보안 권장 사항

### 1. GitHub Private Repository 사용
```bash
# Private repo 클론 시 Personal Access Token 사용
git clone https://TOKEN@github.com/YOUR_USERNAME/NanoGridPlus.git
```

### 2. application-prod.yml 보호
```bash
# .gitignore에 추가 (민감 정보 포함 시)
echo "application-prod.yml" >> .gitignore

# EC2에서 직접 생성
nano src/main/resources/application-prod.yml
```

### 3. IAM Role 최소 권한
- SQS: ReceiveMessage, DeleteMessage만
- S3: GetObject만
- CloudWatch: PutMetricData만

---

## 📊 모니터링

### CloudWatch 로그 전송 (선택)
```bash
# CloudWatch Agent 설치
sudo yum install amazon-cloudwatch-agent -y

# 설정
sudo nano /opt/aws/amazon-cloudwatch-agent/etc/config.json
```

### 시스템 리소스 모니터링
```bash
# CPU/메모리
htop

# 디스크
df -h

# Docker 리소스
docker stats
```

---

## 🎯 체크리스트

### 배포 전
- [ ] GitHub 저장소 생성
- [ ] AWS 리소스 생성 (SQS, S3, IAM)
- [ ] EC2 인스턴스 생성
- [ ] application-prod.yml 설정 확인

### 배포
- [ ] setup-ec2.sh 실행
- [ ] Git 클론
- [ ] deploy-ec2.sh 실행
- [ ] /health 확인
- [ ] /status 확인

### 배포 후
- [ ] 로그 정상 확인
- [ ] SQS 메시지 테스트
- [ ] CloudWatch 메트릭 확인
- [ ] Warm Pool 컨테이너 확인

---

**배포 완료!** 🎉

이제 코드 변경 시 `git push` → EC2에서 `./deploy-ec2.sh`만 실행하면 됩니다.

