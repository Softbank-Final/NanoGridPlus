#!/bin/bash

###############################################################################
# NanoGrid Plus Agent - EC2 초기 설정 스크립트
#
# 사용법:
#   chmod +x setup-ec2.sh
#   ./setup-ec2.sh
###############################################################################

set -e

echo "================================================"
echo "  NanoGrid Plus Agent - EC2 초기 설정"
echo "================================================"

# 1. 시스템 업데이트
echo ""
echo "📦 [1/6] 시스템 업데이트..."
sudo yum update -y

# 2. Docker 설치
echo ""
echo "🐳 [2/6] Docker 설치..."
if ! command -v docker &> /dev/null; then
    sudo yum install docker -y
    sudo systemctl start docker
    sudo systemctl enable docker
    sudo usermod -a -G docker ec2-user
    echo "  ✅ Docker 설치 완료"
else
    echo "  ✅ Docker 이미 설치됨"
fi

# 3. Java 17 설치
echo ""
echo "☕ [3/6] Java 17 설치..."
if ! command -v java &> /dev/null; then
    sudo yum install java-17-amazon-corretto -y
    echo "  ✅ Java 17 설치 완료"
else
    echo "  ✅ Java 이미 설치됨"
fi

# 4. Git 설치
echo ""
echo "📚 [4/6] Git 설치..."
if ! command -v git &> /dev/null; then
    sudo yum install git -y
    echo "  ✅ Git 설치 완료"
else
    echo "  ✅ Git 이미 설치됨"
fi

# 5. Docker 이미지 준비
echo ""
echo "🖼️ [5/6] Docker 이미지 준비..."
echo "  - python-base 이미지 생성 중..."
docker pull python:3.9-slim
docker tag python:3.9-slim python-base

echo "  - gcc-base 이미지 생성 중..."
docker pull gcc:11
docker tag gcc:11 gcc-base

# 6. 작업 디렉터리 생성
echo ""
echo "📁 [6/6] 작업 디렉터리 생성..."
sudo mkdir -p /tmp/task
sudo chmod 777 /tmp/task

echo ""
echo "================================================"
echo "  ✅ 초기 설정 완료!"
echo "================================================"
echo ""
echo "📋 다음 단계:"
echo "  1. 로그아웃 후 재접속 (Docker 그룹 적용)"
echo "     exit"
echo ""
echo "  2. 프로젝트 클론"
echo "     git clone https://github.com/YOUR_REPO/NanoGridPlus.git"
echo "     cd NanoGridPlus"
echo ""
echo "  3. 배포 스크립트 실행"
echo "     chmod +x deploy-ec2.sh"
echo "     ./deploy-ec2.sh"
echo ""

