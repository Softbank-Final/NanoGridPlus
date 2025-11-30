package org.brown.nanogridplus.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK 클라이언트 설정
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class AwsConfig {

    private final AgentProperties agentProperties;

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder()
                .region(Region.of(agentProperties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(agentProperties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public CloudWatchClient cloudWatchClient() {
        return CloudWatchClient.builder()
                .region(Region.of(agentProperties.getAws().getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}

