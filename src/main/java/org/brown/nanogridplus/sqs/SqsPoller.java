package org.brown.nanogridplus.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.brown.nanogridplus.docker.DockerService;
import org.brown.nanogridplus.model.ExecutionResult;
import org.brown.nanogridplus.model.TaskMessage;
import org.brown.nanogridplus.redis.RedisResultPublisher;
import org.brown.nanogridplus.s3.CodeStorageService;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.nio.file.Path;
import java.util.List;

/**
 * SQS Long Polling 기반 작업 수신 및 처리
 * 
 * 7~8단계 안정화:
 * - MDC 기반 requestId 로그 트레이싱
 * - 예외 처리 정책 통일 (실패 시 메시지 재시도)
 * - 한 요청 실패가 전체 Agent를 다운시키지 않음
 * - 상세한 로깅 (FAIL 태그 포함)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.polling.enabled", havingValue = "true", matchIfMissing = true)
public class SqsPoller {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;
    private final CodeStorageService codeStorageService;
    private final DockerService dockerService;
    private final RedisResultPublisher redisResultPublisher;

    /**
     * 주기적으로 SQS 큐를 폴링
     */
    @Scheduled(fixedDelayString = "${agent.polling.fixedDelayMillis:1000}")
    public void pollQueue() {
        try {
            String queueUrl = agentProperties.getSqs().getQueueUrl();

            if (queueUrl == null || queueUrl.isEmpty()) {
                log.warn("SQS Queue URL이 설정되지 않았습니다");
                return;
            }

            log.debug("SQS 메시지 폴링 시작: {}", queueUrl);

            // SQS Long Polling 요청
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(agentProperties.getSqs().getMaxNumberOfMessages())
                    .waitTimeSeconds(agentProperties.getSqs().getWaitTimeSeconds())
                    .build();

            ReceiveMessageResponse receiveResponse = sqsClient.receiveMessage(receiveRequest);
            List<Message> messages = receiveResponse.messages();

            if (messages == null || messages.isEmpty()) {
                log.debug("수신된 메시지가 없습니다");
                return;
            }

            log.info("SQS 메시지 {} 개 수신", messages.size());

            // 각 메시지 처리
            for (Message message : messages) {
                processMessage(queueUrl, message);
            }

        } catch (Exception e) {
            log.error("[FAIL][POLLING] SQS 폴링 중 오류 발생 (Agent는 계속 동작)", e);
            // Agent 전체가 죽지 않도록 예외를 삼킴
        }
    }

    /**
     * 개별 SQS 메시지 처리
     */
    private void processMessage(String queueUrl, Message message) {
        String messageBody = message.body();
        String receiptHandle = message.receiptHandle();
        TaskMessage taskMessage = null;

        try {
            // JSON → TaskMessage 파싱
            taskMessage = objectMapper.readValue(messageBody, TaskMessage.class);
            
            if (taskMessage == null || taskMessage.getRequestId() == null) {
                log.error("[FAIL][PARSE] TaskMessage가 null이거나 requestId가 없습니다");
                deleteMessage(queueUrl, receiptHandle); // 잘못된 메시지는 삭제
                return;
            }

            // MDC에 requestId 설정
            MDC.put("requestId", taskMessage.getRequestId());
            MDC.put("functionId", taskMessage.getFunctionId());
            MDC.put("runtime", taskMessage.getRuntime());

            long startTime = System.currentTimeMillis();

            log.info("===== 작업 메시지 수신 =====");
            log.info("Received task: {}", taskMessage);
            log.info("  - Request ID: {}", taskMessage.getRequestId());
            log.info("  - Function ID: {}", taskMessage.getFunctionId());
            log.info("  - Runtime: {}", taskMessage.getRuntime());
            log.info("  - S3 Location: s3://{}/{}", taskMessage.getS3Bucket(), taskMessage.getS3Key());
            log.info("============================");

            // S3에서 코드 다운로드
            Path workDir = codeStorageService.prepareWorkingDirectory(taskMessage);
            log.info("Prepared working directory at: {}", workDir);

            // Docker 컨테이너 실행
            ExecutionResult result = dockerService.runTask(taskMessage, workDir);

            long totalTime = System.currentTimeMillis() - startTime;

            // 실행 결과 로그
            log.info("===== 실행 결과 =====");
            log.info("Request: {} finished in {}ms", taskMessage.getRequestId(), totalTime);
            log.info("  - Exit Code: {}", result.getExitCode());
            log.info("  - Duration: {}ms", result.getDurationMillis());
            log.info("  - Peak Memory: {} bytes", result.getPeakMemoryBytes());
            log.info("  - Success: {}", result.isSuccess());
            
            if (result.getOptimizationTip() != null) {
                log.info("  - Optimization Tip: {}", result.getOptimizationTip());
            }
            
            log.info("============================");
            log.debug("Stdout:\n{}", result.getStdout());
            log.debug("Stderr:\n{}", result.getStderr());

            // Redis Publish - B팀 Controller에게 결과 전송
            try {
                redisResultPublisher.publishResult(result);
                log.info("✅ [REDIS] 실행 결과 전송 완료: requestId={}", taskMessage.getRequestId());
            } catch (Exception redisEx) {
                log.error("❌ [REDIS][FAIL] 결과 전송 실패 (메시지는 삭제됨): requestId={}",
                         taskMessage.getRequestId(), redisEx);
                // Redis 전송 실패해도 SQS 메시지는 삭제 (실행은 성공했으므로)
            }

            // 정상 처리 완료 - 메시지 삭제
            deleteMessage(queueUrl, receiptHandle);
            log.info("[DONE][OK] requestId={}", taskMessage.getRequestId());

        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("[FAIL][JSON_PARSE] 메시지 파싱 실패: {}", messageBody, e);
            // 파싱 불가능한 메시지는 삭제 (재시도 불필요)
            deleteMessage(queueUrl, receiptHandle);

        } catch (IllegalArgumentException e) {
            log.error("[FAIL][RUNTIME_NOT_SUPPORTED] 지원하지 않는 런타임: {}", 
                    taskMessage != null ? taskMessage.getRuntime() : "unknown", e);
            // 잘못된 런타임 - 메시지 삭제하지 않음 (DLQ로 이동)

        } catch (Exception e) {
            // S3, Docker 등 모든 오류 처리
            String errorType = "UNKNOWN";
            if (e.getMessage() != null) {
                if (e.getMessage().contains("NoSuchKey") || e.getMessage().contains("Not Found")) {
                    errorType = "S3";
                } else if (e.getMessage().contains("docker") || e.getMessage().contains("container")) {
                    errorType = "DOCKER";
                }
            }
            log.error("[FAIL][{}] 실행 중 오류 발생: requestId={}",
                    errorType, taskMessage != null ? taskMessage.getRequestId() : "unknown", e);
            // 메시지 삭제하지 않음 (재시도 가능)

        } finally {
            // MDC 정리
            MDC.clear();
        }
    }

    /**
     * SQS 메시지 삭제
     */
    private void deleteMessage(String queueUrl, String receiptHandle) {
        try {
            DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .receiptHandle(receiptHandle)
                    .build();

            sqsClient.deleteMessage(deleteRequest);
            log.debug("메시지 삭제 완료");

        } catch (Exception e) {
            log.warn("메시지 삭제 실패 (재처리 가능성 있음)", e);
        }
    }
}

