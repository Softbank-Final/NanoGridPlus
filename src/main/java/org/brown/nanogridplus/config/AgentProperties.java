package org.brown.nanogridplus.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 통합 설정 프로퍼티
 * 
 * application.yml의 agent.* 설정을 바인딩
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private AwsConfig aws = new AwsConfig();
    private SqsConfig sqs = new SqsConfig();
    private S3Config s3 = new S3Config();
    private DockerConfig docker = new DockerConfig();
    private WarmPoolConfig warmPool = new WarmPoolConfig();
    private PollingConfig polling = new PollingConfig();
    private RedisConfig redis = new RedisConfig();
    private OutputConfig output = new OutputConfig();  // Output Binding 설정 추가
    private String taskBaseDir = "/tmp/task";

    @Data
    public static class AwsConfig {
        private String region = "ap-northeast-2";
    }

    @Data
    public static class SqsConfig {
        private String queueUrl;
        private int waitTimeSeconds = 20;
        private int maxNumberOfMessages = 10;
    }

    @Data
    public static class S3Config {
        private String codeBucket;
        private String userDataBucket;  // Output 파일 업로드용 버킷
    }

    @Data
    public static class DockerConfig {
        private String pythonImage = "python-base";
        private String cppImage = "gcc-base";
        private String workDirRoot = "/workspace-root";
        private long defaultTimeoutMs = 10000;
        private String outputMountPath = "/output";  // 컨테이너 내부 output 경로
    }

    @Data
    public static class OutputConfig {
        private boolean enabled = true;
        private String baseDir = "/tmp/output";  // 호스트의 output 디렉터리
        private String s3Prefix = "outputs";     // S3 키 프리픽스
    }

    @Data
    public static class WarmPoolConfig {
        private boolean enabled = true;
        private int pythonSize = 2;
        private int cppSize = 1;
    }

    @Data
    public static class PollingConfig {
        private boolean enabled = true;
        private long fixedDelayMillis = 1000;
    }

    @Data
    public static class RedisConfig {
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private String resultPrefix = "result:";
    }

    // Convenience methods
    public String getRegion() {
        return aws.getRegion();
    }
}

