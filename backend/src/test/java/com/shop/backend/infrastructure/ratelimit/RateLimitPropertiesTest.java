package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.presentation.web.ClientIpResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the real application.yml and checks that rate-limit.* and the Redis timeouts bind as intended. */
class RateLimitPropertiesTest {

    private final ScriptedRedis redis = new ScriptedRedis();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(StringRedisTemplate.class, () -> redis.template)
            .withUserConfiguration(RedisRateLimiter.class, ClientIpResolver.class);

    @Test
    void 성공_application_yml의_한도는_5회_5분_hops_1로_바인딩된다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            RateLimiter.Attempt attempt = context.getBean(RateLimiter.class).acquireLogin("1.1.1.1");
            attempt.countAndRelease();
            // window seconds = 5 min * 60, max attempts = 5
            assertThat(redis.calls.get(1).args().subList(0, 2)).containsExactly("300", "5");

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "1.1.1.1, 9.9.9.9");
            assertThat(context.getBean(ClientIpResolver.class).resolve(request)).isEqualTo("9.9.9.9"); // hops = 1
        });
    }

    @Test
    void 성공_Redis_타임아웃은_500ms로_설정된다() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("spring.data.redis.timeout")).isEqualTo("500ms");
            assertThat(context.getEnvironment().getProperty("spring.data.redis.connect-timeout")).isEqualTo("500ms");
        });
    }
}
