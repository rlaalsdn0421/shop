package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.domain.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Runs RedisRateLimiter against a pure-Java model of INCR/EXPIRE/SET EX/DEL/PTTL with a controllable
 * clock. This verifies the intended ALGORITHM (lock counted from the last failure, no extension while
 * locked, restart after expiry), NOT the Lua text, which only a real Redis can execute.
 */
class RedisRateLimiterSimulationTest {

    /** Minimal Redis model: string values with optional absolute expiry in ms. */
    static class FakeRedis {
        long nowMs = 0;
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Long> expiresAt = new HashMap<>();

        private void purge() {
            expiresAt.entrySet().removeIf(e -> {
                if (e.getValue() <= nowMs) {
                    values.remove(e.getKey());
                    return true;
                }
                return false;
            });
        }

        long incr(String key) {
            purge();
            long v = values.containsKey(key) ? Long.parseLong(values.get(key)) + 1 : 1;
            values.put(key, String.valueOf(v));
            return v;
        }

        void expire(String key, long seconds) {
            expiresAt.put(key, nowMs + seconds * 1000);
        }

        void setEx(String key, long seconds) {
            values.put(key, "1");
            expire(key, seconds);
        }

        boolean del(String key) {
            purge();
            expiresAt.remove(key);
            return values.remove(key) != null;
        }

        /** Redis PTTL: -2 missing, -1 no expiry, else remaining ms. */
        long pttl(String key) {
            purge();
            if (!values.containsKey(key)) {
                return -2;
            }
            return expiresAt.containsKey(key) ? expiresAt.get(key) - nowMs : -1;
        }

        /** Same steps as RedisRateLimiter.COUNT_SCRIPT. */
        long countScript(List<String> keys, String windowSeconds, String maxAttempts) {
            long c = incr(keys.get(0));
            if (c == 1) {
                expire(keys.get(0), Long.parseLong(windowSeconds));
            }
            if (c >= Long.parseLong(maxAttempts)) {
                setEx(keys.get(1), Long.parseLong(windowSeconds));
                del(keys.get(0));
                return 1;
            }
            return 0;
        }
    }

    private final FakeRedis fake = new FakeRedis();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, inv -> {
        Object[] args = inv.getArguments();
        return switch (inv.getMethod().getName()) {
            case "getExpire" -> fake.pttl((String) args[0]);
            case "delete" -> fake.del((String) args[0]);
            case "execute" -> fake.countScript((List<String>) args[1], (String) args[2], (String) args[3]);
            default -> null;
        };
    });
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, 5, 5);

    private void at(long minutes, long seconds) {
        fake.nowMs = (minutes * 60 + seconds) * 1000;
    }

    private void failLogins(int times) {
        for (int i = 0; i < times; i++) {
            limiter.assertLoginAllowed("1.1.1.1");
            limiter.recordLoginFailure("1.1.1.1");
        }
    }

    private long retryAfter(Runnable call) {
        TooManyRequestsException ex = null;
        try {
            call.run();
        } catch (TooManyRequestsException e) {
            ex = e;
        }
        assertThat(ex).as("expected 429").isNotNull();
        return ex.getRetryAfterSeconds();
    }

    @Test
    void 성공_5번째_실패는_그_시점부터_5분간_잠그고_처음_실패_기준이_아니다() {
        at(0, 0);
        failLogins(1);
        at(4, 50);
        failLogins(4); // 5th failure here

        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(300);

        at(5, 0); // first failure's window would be over: still locked
        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(290);

        at(9, 49);
        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(1);

        at(9, 50);
        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_잠긴_동안_요청은_잠금을_연장하지_않는다() {
        at(0, 0);
        failLogins(5);

        at(2, 0);
        // blocked requests are rejected before any credential check and never recorded by the controller
        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(180);
        at(4, 0);
        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(60);
        at(5, 0);
        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_잠금이_풀리면_횟수는_0부터_다시_센다() {
        at(0, 0);
        failLogins(5);
        at(5, 0);

        failLogins(4); // 4 new failures: not locked yet
        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();

        failLogins(1); // 5th of the new round locks again
        assertThat(retryAfter(() -> limiter.assertLoginAllowed("1.1.1.1"))).isEqualTo(300);
    }

    @Test
    void 성공_첫_실패로부터_윈도우가_지나면_카운터가_새로_시작한다() {
        at(0, 0);
        failLogins(4);
        at(5, 1); // counter expired

        failLogins(1);

        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_다른_IP는_서로_잠그지_않는다() {
        at(0, 0);
        failLogins(5);

        assertThatCode(() -> limiter.assertLoginAllowed("2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void 성공_회원가입_5번째_요청은_처리되고_이후_5분간_429이다() {
        at(0, 0);
        for (int i = 0; i < 5; i++) {
            limiter.checkAndRecordRegister("1.1.1.1"); // the 5th is still allowed
        }

        at(1, 0);
        assertThat(retryAfter(() -> limiter.checkAndRecordRegister("1.1.1.1"))).isEqualTo(240);

        at(5, 0);
        assertThatCode(() -> limiter.checkAndRecordRegister("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 실패_회원가입_잠금은_로그인_잠금과_별개다() {
        at(0, 0);
        failLogins(5);

        assertThatCode(() -> limiter.checkAndRecordRegister("1.1.1.1")).doesNotThrowAnyException();
    }
}
