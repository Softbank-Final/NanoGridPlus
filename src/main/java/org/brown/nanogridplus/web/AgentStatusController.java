package org.brown.nanogridplus.web;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.brown.nanogridplus.config.AgentProperties;
import org.brown.nanogridplus.docker.WarmPoolManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 상태 확인 API
 * 
 * 엔드포인트:
 * - GET /health: 간단한 헬스체크
 * - GET /status: 상세한 Agent 상태 정보
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AgentStatusController {

    private final AgentProperties agentProperties;

    /**
     * 간단한 헬스체크
     * 
     * @return "OK"
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    /**
     * 상세한 Agent 상태
     * 
     * @return Agent 상태 정보 (JSON)
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        
        status.put("status", "UP");
        status.put("application", "NanoGridPlus Agent");
        status.put("region", agentProperties.getAws().getRegion());
        
        // Warm Pool 정보
        Map<String, Object> warmPool = new HashMap<>();
        warmPool.put("enabled", agentProperties.getWarmPool().isEnabled());
        warmPool.put("pythonSize", agentProperties.getWarmPool().getPythonSize());
        warmPool.put("cppSize", agentProperties.getWarmPool().getCppSize());
        status.put("warmPool", warmPool);
        
        // SQS 정보
        Map<String, Object> sqs = new HashMap<>();
        sqs.put("enabled", agentProperties.getPolling().isEnabled());
        sqs.put("queueUrl", maskSensitiveUrl(agentProperties.getSqs().getQueueUrl()));
        status.put("sqs", sqs);
        
        // Docker 정보
        Map<String, Object> docker = new HashMap<>();
        docker.put("pythonImage", agentProperties.getDocker().getPythonImage());
        docker.put("cppImage", agentProperties.getDocker().getCppImage());
        status.put("docker", docker);
        
        log.info("Status check requested");
        return status;
    }

    /**
     * 민감한 URL 마스킹
     */
    private String maskSensitiveUrl(String url) {
        if (url == null) return "N/A";
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash > 0) {
            return url.substring(0, lastSlash + 1) + "***";
        }
        return "***";
    }
}

