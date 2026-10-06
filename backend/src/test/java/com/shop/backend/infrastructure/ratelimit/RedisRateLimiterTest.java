package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mocked Redis: checks which scripts run with which keys and args, not the Lua text itself. */
class RedisRateLimiterTest {

    private final ScriptedRedis redis = new ScriptedRedis();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis.template, 5, 5, now::get, 10_000);

    private void assertBlocked(Runnable call, long retrySeconds, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
            assertThat(ex.getRetryAfterSeconds()).isEqualTo(retrySeconds);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void 성공_잠금이_없으면_예상한_키와_인자로_획득한다() {
        redis.handler = (script, keys, args) -> 0L;

        limiter.acquireLogin("1.1.1.1");

        assertThat(redis.calls).hasSize(1);
        ScriptedRedis.Call call = redis.calls.get(0);
        assertThat(call.script()).isEqualTo(RedisRateLimiter.ACQUIRE_SCRIPT);
        assertThat(call.keys()).containsExactly("shop:rl:login:lock:1.1.1.1", "shop:rl:login:busy:1.1.1.1");
        assertThat(call.args().get(0)).isEqualTo("10"); // busy key TTL seconds
        assertThat(call.args().get(1)).isNotBlank(); // per-attempt token
    }

    @Test
    void 실패_잠겨_있으면_남은_시간을_초로_올림해_429를_던진다() {
        redis.handler = (script, keys, args) -> 299_500L;

        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 300, "시도 횟수를 초과했습니다. 5분 후에 다시 시도해 주세요.");
    }

    @Test
    void 실패_남은_시간이_1ms여도_최소_1초_1분이다() {
        redis.handler = (script, keys, args) -> 1L;

        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 1, "시도 횟수를 초과했습니다. 1분 후에 다시 시도해 주세요.");
    }

    @Test
    void 실패_분은_올림한다() {
        redis.handler = (script, keys, args) -> 61_000L;

        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 61, "시도 횟수를 초과했습니다. 2분 후에 다시 시도해 주세요.");
    }

    @Test
    void 실패_이미_처리_중이면_busy_메시지와_Retry_After_1초로_429를_던진다() {
        redis.handler = (script, keys, args) -> -1L;

        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 1, "요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    void 성공_실패_기록은_세_키와_획득할_때의_토큰으로_실행하고_이후_release는_무시한다() {
        redis.handler = (script, keys, args) -> 0L;
        RateLimiter.Attempt attempt = limiter.acquireLogin("1.1.1.1");
        String token = redis.calls.get(0).args().get(1);

        attempt.countAndRelease();
        attempt.release();
        attempt.countAndRelease();

        assertThat(redis.calls).hasSize(2);
        ScriptedRedis.Call call = redis.calls.get(1);
        assertThat(call.script()).isEqualTo(RedisRateLimiter.COUNT_RELEASE_SCRIPT);
        assertThat(call.keys()).containsExactly(
                "shop:rl:login:count:1.1.1.1", "shop:rl:login:lock:1.1.1.1", "shop:rl:login:busy:1.1.1.1");
        assertThat(call.args()).containsExactly("300", "5", token);
    }

    @Test
    void 성공_release는_자기_토큰으로_busy만_지우고_한_번만_실행한다() {
        redis.handler = (script, keys, args) -> 0L;
        RateLimiter.Attempt attempt = limiter.acquireLogin("1.1.1.1");
        String token = redis.calls.get(0).args().get(1);

        attempt.release();
        attempt.release();
        attempt.countAndRelease();

        assertThat(redis.calls).hasSize(2);
        ScriptedRedis.Call call = redis.calls.get(1);
        assertThat(call.script()).isEqualTo(RedisRateLimiter.RELEASE_SCRIPT);
        assertThat(call.keys()).containsExactly("shop:rl:login:busy:1.1.1.1");
        assertThat(call.args()).containsExactly(token);
    }

    @Test
    void 성공_획득하지_못한_요청은_아무것도_해제하지_않는다() {
        redis.handler = (script, keys, args) -> -1L;

        assertThatThrownBy(() -> limiter.acquireLogin("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);

        assertThat(redis.calls).hasSize(1); // only the acquire attempt itself, no release/count
    }

    @Test
    void 성공_회원가입은_로그인과_다른_키를_쓴다() {
        redis.handler = (script, keys, args) -> 0L;

        limiter.acquireRegister("1.1.1.1").countAndRelease();

        assertThat(redis.calls.get(0).keys())
                .containsExactly("shop:rl:register:lock:1.1.1.1", "shop:rl:register:busy:1.1.1.1");
        assertThat(redis.calls.get(1).keys()).containsExactly(
                "shop:rl:register:count:1.1.1.1", "shop:rl:register:lock:1.1.1.1", "shop:rl:register:busy:1.1.1.1");
    }

    @Test
    void 성공_IP가_다르면_키가_다르다() {
        redis.handler = (script, keys, args) -> 0L;

        limiter.acquireLogin("1.1.1.1");
        limiter.acquireLogin("2001:db8:1:2::/64");

        assertThat(redis.calls.get(0).keys()).allMatch(k -> k.endsWith(":1.1.1.1"));
        assertThat(redis.calls.get(1).keys()).allMatch(k -> k.endsWith(":2001:db8:1:2::/64"));
    }

    @Test
    void 성공_잠금이_확인된_IP는_만료될_때까지_Redis를_다시_호출하지_않는다() {
        redis.handler = (script, keys, args) -> 120_000L;
        assertThatThrownBy(() -> limiter.acquireLogin("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);

        now.addAndGet(60_000);
        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 60, "시도 횟수를 초과했습니다. 1분 후에 다시 시도해 주세요.");
        assertThat(redis.calls).hasSize(1);

        now.addAndGet(60_000); // lock expired -> Redis is asked again
        redis.handler = (script, keys, args) -> 0L;
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1")).doesNotThrowAnyException();
        assertThat(redis.calls).hasSize(2);
    }

    @Test
    void 성공_잠금이_생긴_실패_기록_뒤에는_Redis_호출_없이_429이다() {
        redis.handler = (script, keys, args) -> script.equals(RedisRateLimiter.COUNT_RELEASE_SCRIPT) ? 1L : 0L;
        limiter.acquireLogin("1.1.1.1").countAndRelease();
        int callsSoFar = redis.calls.size();

        assertBlocked(() -> limiter.acquireLogin("1.1.1.1"), 300, "시도 횟수를 초과했습니다. 5분 후에 다시 시도해 주세요.");
        assertThat(redis.calls).hasSize(callsSoFar);
    }

    @Test
    void 성공_잠금_캐시는_항목_수_제한을_지킨다() {
        RedisRateLimiter small = new RedisRateLimiter(redis.template, 5, 5, now::get, 3);
        redis.handler = (script, keys, args) -> 120_000L;

        for (int i = 0; i < 10; i++) {
            String ip = "10.0.0." + i;
            assertThatThrownBy(() -> small.acquireLogin(ip)).isInstanceOf(TooManyRequestsException.class);
        }

        assertThat(small.lockCacheSize()).isLessThanOrEqualTo(3);
    }

    @Test
    void 성공_Redis_오류면_로컬_제한기로_넘어가고_예외를_던지지_않는다() {
        redis.handler = (script, keys, args) -> {
            throw new RedisConnectionFailureException("down");
        };

        assertThatCode(() -> {
            limiter.acquireLogin("1.1.1.1").release();
            RateLimiter.Attempt attempt = limiter.acquireRegister("1.1.1.1");
            attempt.countAndRelease();
            attempt.release();
        }).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis가_빈_결과를_주면_로컬_제한기로_넘어간다() {
        redis.handler = (script, keys, args) -> null;

        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").countAndRelease()).doesNotThrowAnyException();
    }

    @Test
    void 성공_획득_뒤_Redis_오류가_나도_실패_기록과_해제는_예외_없이_끝난다() {
        redis.handler = (script, keys, args) -> {
            if (script.equals(RedisRateLimiter.ACQUIRE_SCRIPT)) {
                return 0L;
            }
            throw new RedisConnectionFailureException("down");
        };

        RateLimiter.Attempt failed = limiter.acquireLogin("1.1.1.1");
        RateLimiter.Attempt succeeded = limiter.acquireLogin("2.2.2.2");

        assertThatCode(() -> {
            failed.countAndRelease();
            succeeded.release();
        }).doesNotThrowAnyException();
        assertThat(limiter.fallbackSize()).isEqualTo(1); // the failure was kept locally
    }
}
