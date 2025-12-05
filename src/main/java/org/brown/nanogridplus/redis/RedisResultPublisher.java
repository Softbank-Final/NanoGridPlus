package org.brown.nanogridplus.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.brown.nanogridplus.model.ExecutionResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis Pub/Sub을 통해 실행 결과를 Controller에게 전송하는 서비스
 * B팀 Controller는 result:{requestId} 채널을 구독하며 결과를 대기함
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisResultPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProperties;

    /**
     * 실행 결과를 Redis Pub/Sub 채널로 전송
     * @param result 실행 결과 (ExecutionResult)
     */
    public void publishResult(ExecutionResult result) {
        String requestId = result.getRequestId();
        String channel = agentProperties.getRedis().getResultPrefix() + requestId;

        try {
            // B팀 Controller가 기대하는 JSON 형식으로 변환
            Map<String, Object> payload = buildPayload(result);
            String jsonMessage = objectMapper.writeValueAsString(payload);

            log.info("📤 [REDIS] Publishing result to channel: {} (requestId={})", channel, requestId);
            log.info("   Redis Host: {}", agentProperties.getRedis().getHost());
            log.debug("   Payload: {}", jsonMessage);

            // Redis Publish - 구독자 수 반환됨
            Long subscriberCount = redisTemplate.convertAndSend(channel, jsonMessage);

            if (subscriberCount != null && subscriberCount > 0) {
                log.info("✅ [REDIS] Result published successfully for requestId={}, subscribers={}", requestId, subscriberCount);
            } else {
                log.warn("⚠️ [REDIS] Result published but NO SUBSCRIBERS on channel: {} (requestId={})", channel, requestId);
                log.warn("   ⚠️ Controller may have timed out or not subscribed yet!");
            }

        } catch (Exception e) {
            log.error("❌ [REDIS][FAIL] Failed to publish result for requestId={} to channel={}",
                     requestId, channel, e);
            // Redis 전송 실패해도 Worker는 계속 동작 (로그만 남기고 예외 삼킴)
        }
    }

    /**
     * B팀 Controller가 기대하는 응답 형식으로 변환
     */
    private Map<String, Object> buildPayload(ExecutionResult result) {
        Map<String, Object> payload = new HashMap<>();

        // 필수 필드
        payload.put("requestId", result.getRequestId());
        payload.put("functionId", result.getFunctionId());
        payload.put("status", result.isSuccess() ? "SUCCESS" : "FAILED");
        payload.put("exitCode", result.getExitCode());
        payload.put("durationMillis", result.getDurationMillis());

        // 실행 로그
        payload.put("stdout", result.getStdout() != null ? result.getStdout() : "");
        payload.put("stderr", result.getStderr() != null ? result.getStderr() : "");

        // Auto-Tuner 메트릭 (있으면 추가)
        if (result.getPeakMemoryBytes() != null) {
            payload.put("peakMemoryBytes", result.getPeakMemoryBytes());
            payload.put("peakMemoryMB", result.getPeakMemoryBytes() / (1024 * 1024));
        }

        // 최적화 팁 (있으면 추가)
        if (result.getOptimizationTip() != null && !result.getOptimizationTip().isEmpty()) {
            payload.put("optimizationTip", result.getOptimizationTip());
        }

        return payload;
    }
}

