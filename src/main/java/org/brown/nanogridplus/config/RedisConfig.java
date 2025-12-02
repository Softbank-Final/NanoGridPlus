package org.brown.nanogridplus.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@Configuration
public class RedisConfig {

    private final AgentProperties agentProperties;

    public RedisConfig(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        log.info("🔌 Redis 연결 설정: {}:{}",
                 agentProperties.getRedis().getHost(),
                 agentProperties.getRedis().getPort());

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(agentProperties.getRedis().getHost());
        config.setPort(agentProperties.getRedis().getPort());

        String password = agentProperties.getRedis().getPassword();
        if (password != null && !password.isEmpty()) {
            config.setPassword(password);
        }

        return new LettuceConnectionFactory(config);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}

