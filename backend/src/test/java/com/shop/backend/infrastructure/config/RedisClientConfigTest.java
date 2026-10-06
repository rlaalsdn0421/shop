package com.shop.backend.infrastructure.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClientConfigTest {

    private ClientOptions customized(RedisProperties properties) {
        var builder = LettuceClientConfiguration.builder();
        new RedisClientConfig().lettuceClientOptionsCustomizer(properties).customize(builder);
        return builder.build().getClientOptions().orElseThrow();
    }

    @Test
    void 성공_연결이_끊기면_명령을_거절하고_타임아웃을_켜며_재연결은_유지한다() {
        RedisProperties properties = new RedisProperties();
        properties.setConnectTimeout(Duration.ofMillis(500));

        ClientOptions options = customized(properties);

        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
        assertThat(options.isAutoReconnect()).isTrue();
    }

    @Test
    void 성공_기존_연결_타임아웃_500ms를_유지한다() {
        RedisProperties properties = new RedisProperties();
        properties.setConnectTimeout(Duration.ofMillis(500));

        assertThat(customized(properties).getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void 실패_연결_타임아웃이_설정되지_않아도_예외_없이_옵션을_만든다() {
        assertThat(customized(new RedisProperties()).getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
