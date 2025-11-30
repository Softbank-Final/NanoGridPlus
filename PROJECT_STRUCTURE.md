# NanoGrid Plus 프로젝트 파일 트리

```
NanoGridPlus/
├── build.gradle                                    # Gradle 빌드 설정
├── settings.gradle                                 # Gradle 프로젝트 설정
├── gradlew                                         # Gradle Wrapper (Unix)
├── gradlew.bat                                     # Gradle Wrapper (Windows)
├── HELP.md                                         # Spring Initializr 도움말
├── README.md                                       # 📖 프로젝트 README
├── IMPLEMENTATION_REPORT.md                        # 📋 구현 완료 보고서
│
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
│
├── build/                                          # 빌드 출력 디렉터리
│   ├── classes/
│   ├── libs/
│   │   └── NanoGridPlus-0.0.1-SNAPSHOT.jar        # 실행 가능한 JAR
│   └── reports/
│
└── src/
    ├── main/
    │   ├── java/
    │   │   └── org/
    │   │       └── brown/
    │   │           └── nanogridplus/
    │   │               │
    │   │               ├── NanoGridPlusApplication.java    # 🚀 Spring Boot Entry Point
    │   │               │
    │   │               ├── config/                         # ⚙️ 설정 클래스
    │   │               │   ├── AgentConfig.java           # Agent 폴링/Warm Pool 설정
    │   │               │   ├── AgentProperties.java       # AWS 설정 (SQS, S3)
    │   │               │   ├── AwsConfig.java             # AWS SDK Bean 설정
    │   │               │   └── JacksonConfig.java         # JSON 파싱 설정
    │   │               │
    │   │               ├── model/                          # 📦 데이터 모델
    │   │               │   └── TaskMessage.java           # SQS 메시지 DTO
    │   │               │
    │   │               ├── sqs/                            # 📨 SQS 관련
    │   │               │   └── SqsPoller.java             # ✅ SQS Long Polling 구현
    │   │               │
    │   │               ├── s3/                             # 📥 S3 관련
    │   │               │   └── CodeStorageService.java    # 🚧 S3 코드 다운로드 (향후 구현)
    │   │               │
    │   │               ├── docker/                         # 🐳 Docker 관련
    │   │               │   └── DockerService.java         # 🚧 Docker 실행 (향후 구현)
    │   │               │
    │   │               ├── warmup/                         # 🔥 Warm Pool 관련
    │   │               │   └── WarmPoolManager.java       # 🚧 Warm Pool 관리 (향후 구현)
    │   │               │
    │   │               └── metrics/                        # 📊 메트릭 관련
    │   │                   └── ResourceMonitor.java       # 🚧 리소스 모니터링 (향후 구현)
    │   │
    │   └── resources/
    │       ├── application.yml                            # 🔧 애플리케이션 설정
    │       ├── application.properties                     # (레거시)
    │       ├── static/                                     # 정적 리소스
    │       └── templates/                                  # 템플릿
    │
    └── test/
        └── java/
            └── org/
                └── brown/
                    └── nanogridplus/
                        └── NanoGridPlusApplicationTests.java

```

## 파일 개수 요약

### 구현된 Java 클래스: 11개
- ✅ Entry Point: 1개
- ✅ Config: 4개
- ✅ Model: 1개
- ✅ SQS: 1개 (완전 구현)
- 🚧 S3: 1개 (빈 껍데기)
- 🚧 Docker: 1개 (빈 껍데기)
- 🚧 Warm Pool: 1개 (빈 껍데기)
- 🚧 Metrics: 1개 (빈 껍데기)

### 설정 파일: 2개
- application.yml (주 설정)
- application.properties (레거시)

### 문서: 3개
- README.md
- IMPLEMENTATION_REPORT.md
- HELP.md

### 빌드 파일: 2개
- build.gradle
- settings.gradle

---

## 각 파일 설명

### 🚀 Entry Point
**NanoGridPlusApplication.java**
- Spring Boot 애플리케이션 시작점
- `@SpringBootApplication` 어노테이션
- main 메서드

### ⚙️ Config 패키지

**AgentProperties.java**
- AWS 설정 바인딩 (region, SQS, S3)
- `@ConfigurationProperties(prefix = "aws")`
- SqsConfig, S3Config 내부 클래스

**AgentConfig.java**
- Agent 동작 설정 (polling, warmPool)
- `@ConfigurationProperties(prefix = "agent")`
- PollingConfig, WarmPoolConfig 내부 클래스

**AwsConfig.java**
- AWS SDK Bean 생성
- SqsClient Bean
- DefaultCredentialsProvider 사용
- `@EnableScheduling`

**JacksonConfig.java**
- ObjectMapper Bean 생성
- JSON 파싱 설정
- 알 수 없는 속성 무시 설정

### 📦 Model 패키지

**TaskMessage.java**
- SQS 메시지 DTO
- requestId, functionId, runtime, s3Bucket, s3Key, timeoutMs
- Jackson `@JsonProperty` 어노테이션
- Lombok `@Data`

### 📨 SQS 패키지

**SqsPoller.java** ✅ **완전 구현**
- `@Scheduled` Long Polling
- SQS 메시지 수신 (최대 10개, 20초 대기)
- JSON 파싱 (ObjectMapper)
- 메시지 삭제
- 에러 처리 및 로깅

### 📥 S3 패키지

**CodeStorageService.java** 🚧 **빈 껍데기**
- `downloadCode()` - S3 코드 다운로드
- `extractZip()` - zip 압축 해제
- 향후 구현 예정

### 🐳 Docker 패키지

**DockerService.java** 🚧 **빈 껍데기**
- `executeInContainer()` - 컨테이너 실행
- `stopContainer()` - 컨테이너 중지
- `removeContainer()` - 컨테이너 제거
- 향후 구현 예정

### 🔥 Warm Pool 패키지

**WarmPoolManager.java** 🚧 **빈 껍데기**
- `initialize()` - Warm Pool 초기화 (`@PostConstruct`)
- `acquireContainer()` - Pool에서 컨테이너 가져오기
- `releaseContainer()` - Pool에 컨테이너 반환
- `cleanup()` - Pool 정리 (`@PreDestroy`)
- 향후 구현 예정

### 📊 Metrics 패키지

**ResourceMonitor.java** 🚧 **빈 껍데기**
- `getMemoryUsage()` - 메모리 사용량 측정
- `getCpuUsage()` - CPU 사용량 측정
- `collectStats()` - 종합 통계 수집
- ResourceStats 내부 클래스
- 향후 구현 예정

---

## 빌드 출력

### JAR 파일
`build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar`

실행 방법:
```bash
java -jar build/libs/NanoGridPlus-0.0.1-SNAPSHOT.jar
```

---

**생성일**: 2025-11-30  
**프로젝트**: NanoGrid Plus - Smart Worker Agent  
**단계**: 0~1단계 완료

