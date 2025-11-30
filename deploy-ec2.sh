#!/bin/bash

###############################################################################
# NanoGrid Plus Agent - EC2 배포 스크립트
#
# 사용법:
#   chmod +x deploy-ec2.sh
#   ./deploy-ec2.sh
###############################################################################

set -e  # 에러 발생 시 즉시 종료

echo "================================================"
echo "  NanoGrid Plus Agent - EC2 배포 시작"
echo "================================================"

# 1. 프로젝트 디렉터리로 이동
cd /home/ec2-user/NanoGridPlus

# 2. 최신 코드 가져오기
echo ""
echo "📥 [1/5] Git pull..."
git pull origin main

# 3. 실행 중인 Agent 중지
echo ""
echo "🛑 [2/5] 기존 Agent 중지..."
if [ -f agent.pid ]; then
    OLD_PID=$(cat agent.pid)
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo "  - PID $OLD_PID 프로세스 종료 중..."
        kill $OLD_PID
        sleep 3

        # 강제 종료 필요 시
        if ps -p $OLD_PID > /dev/null 2>&1; then
            echo "  - 강제 종료 중..."
            kill -9 $OLD_PID
        fi
    fi
    rm -f agent.pid
fi

# 4. Gradle 빌드
echo ""
echo "🔨 [3/5] Gradle 빌드..."
./gradlew clean bootJar

# 5. Docker 이미지 확인
echo ""
echo "🐳 [4/5] Docker 이미지 확인..."
if ! docker images | grep -q "python-base"; then
    echo "  - python-base 이미지 생성 중..."
    docker pull python:3.9-slim
    docker tag python:3.9-slim python-base
fi

if ! docker images | grep -q "gcc-base"; then
    echo "  - gcc-base 이미지 생성 중..."
    docker pull gcc:11
    docker tag gcc:11 gcc-base
fi

# 6. Agent 시작
echo ""
echo "🚀 [5/5] Agent 시작..."
nohup java -jar \
    build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar \
    --spring.profiles.active=prod \
    > /home/ec2-user/nanogrid-agent.log 2>&1 &

# PID 저장
echo $! > agent.pid

echo ""
echo "================================================"
echo "  ✅ 배포 완료!"
echo "================================================"
echo ""
echo "📊 상태 확인:"
echo "  - PID: $(cat agent.pid)"
echo "  - 로그: tail -f /home/ec2-user/nanogrid-agent.log"
echo "  - Health: curl http://localhost:8080/health"
echo "  - Status: curl http://localhost:8080/status"
echo ""
echo "🛑 중지: kill $(cat agent.pid)"
echo ""

