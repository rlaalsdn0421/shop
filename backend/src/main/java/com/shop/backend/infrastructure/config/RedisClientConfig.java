package com.shop.backend.infrastructure.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A down Redis must not queue commands in memory, replay stale INCR/SET after recovery, or hold request threads:
 * reject commands while disconnected and enforce command timeouts. Replaces Boot's ClientOptions, so the
 * configured connect timeout is re-applied here (the command timeout stays with spring.data.redis.timeout).
 */
@Configuration
public class RedisClientConfig {

    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientOptionsCustomizer(RedisProperties properties) {
        return builder -> {
            ClientOptions.Builder options = ClientOptions.builder()
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .timeoutOptions(TimeoutOptions.enabled());
            if (properties.getConnectTimeout() != null) {
                options.socketOptions(SocketOptions.builder().connectTimeout(properties.getConnectTimeout()).build());
            }
            builder.clientOptions(options.build());
        };
    }
}
