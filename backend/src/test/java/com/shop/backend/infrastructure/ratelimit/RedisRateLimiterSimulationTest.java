package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs RedisRateLimiter against FakeRedis, a pure-Java model of the Redis commands and Lua steps with a
 * controllable clock. This verifies the intended ALGORITHM (lock counted from the last failure, no extension
 * while locked, busy guard, fallback), NOT the Lua text, which only a real Redis can execute.
 */
class RedisRateLimiterSimulationTest {

    private static final String BUSY_MESSAGE = "요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.";

    private final FakeRedis fake = new FakeRedis();
    private final ScriptedRedis redis = new ScriptedRedis();
    private final RedisRateLimiter limiter;

    RedisRateLimiterSimulationTest() {
        redis.handler = fake;
        limiter = new RedisRateLimiter(redis.template, 5, 5, () -> fake.nowMs, 10_000);
    }

    private void at(long minutes, long seconds) {
        fake.nowMs = (minutes * 60 + seconds) * 1000;
    }

    /** One failed login attempt: acquire, then count (what the controller does on InvalidCredentialsException). */
    private void failLogin(String ip) {
        RateLimiter.Attempt attempt = limiter.acquireLogin(ip);
        attempt.countAndRelease();
        attempt.release();
    }

    private void failLogins(int times) {
        for (int i = 0; i < times; i++) {
            failLogin("1.1.1.1");
        }
    }

    private TooManyRequestsException blocked(Runnable call) {
        try {
            call.run();
        } catch (TooManyRequestsException e) {
            return e;
        }
        throw new AssertionError("expected 429");
    }

    @Test
    void 성공_5번째_실패는_그_시점부터_5분간_잠그고_처음_실패_기준이_아니다() {
        at(0, 0);
        failLogins(1);
        at(4, 50);
        failLogins(4); // the 5th failure happens here

        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);

        at(5, 0); // the first failure's window would be over: still locked
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(290);

        at(9, 49);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(1);

        at(9, 50);
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_잠긴_동안_요청은_세지_않고_잠금을_연장하지_않는다() {
        at(0, 0);
        failLogins(5);

        at(2, 0);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(180);
        at(4, 0);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(60);
        at(5, 0);
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_성공한_로그인은_실패_횟수를_지우지_않는다() {
        at(0, 0);
        failLogins(4);

        limiter.acquireLogin("1.1.1.1").release(); // successful login: acquire + release, nothing counted or cleared
        failLogins(1);

        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
    }

    @Test
    void 성공_잠금이_풀리면_횟수는_0부터_다시_센다() {
        at(0, 0);
        failLogins(5);
        at(5, 0);

        failLogins(4);
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();

        failLogins(1);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
    }

    @Test
    void 성공_첫_실패로부터_윈도우가_지나면_카운터가_새로_시작한다() {
        at(0, 0);
        failLogins(4);
        at(5, 1);

        failLogins(1);

        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_다른_IP는_서로_잠그지_않는다() {
        at(0, 0);
        failLogins(5);

        assertThatCode(() -> limiter.acquireLogin("2.2.2.2").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_회원가입_5번째_요청은_처리되고_이후_5분간_429이다() {
        at(0, 0);
        for (int i = 0; i < 5; i++) {
            RateLimiter.Attempt attempt = limiter.acquireRegister("1.1.1.1"); // the 5th is still allowed
            attempt.countAndRelease();
        }

        at(1, 0);
        assertThat(blocked(() -> limiter.acquireRegister("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(240);

        at(5, 0);
        assertThatCode(() -> limiter.acquireRegister("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_로그인_실패는_회원가입_카운터와_잠금에_영향을_주지_않는다() {
        at(0, 0);
        failLogins(5);

        assertThat(fake.exists("shop:rl:register:count:1.1.1.1")).isFalse();
        assertThat(fake.exists("shop:rl:register:lock:1.1.1.1")).isFalse();
        assertThatCode(() -> limiter.acquireRegister("1.1.1.1").countAndRelease()).doesNotThrowAnyException();
        assertThat(fake.get("shop:rl:login:count:1.1.1.1")).isNull(); // reset by the lock
        assertThat(fake.exists("shop:rl:login:lock:1.1.1.1")).isTrue();
    }

    @Test
    void 성공_회원가입_5회는_로그인_카운터를_건드리지_않는다() {
        at(0, 0);
        for (int i = 0; i < 4; i++) {
            limiter.acquireRegister("1.1.1.1").countAndRelease();
        }

        assertThat(fake.exists("shop:rl:login:count:1.1.1.1")).isFalse();
        assertThat(fake.get("shop:rl:register:count:1.1.1.1")).isEqualTo("4");
    }

    @Test
    void 실패_처리_중인_같은_IP의_두_번째_시도는_busy_429이고_해제하면_다시_된다() {
        at(0, 0);
        RateLimiter.Attempt first = limiter.acquireLogin("1.1.1.1");

        TooManyRequestsException busy = blocked(() -> limiter.acquireLogin("1.1.1.1"));
        assertThat(busy.getMessage()).isEqualTo(BUSY_MESSAGE);
        assertThat(busy.getRetryAfterSeconds()).isEqualTo(1);

        first.release();
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_다른_IP나_다른_종류는_busy를_공유하지_않는다() {
        limiter.acquireLogin("1.1.1.1");

        assertThatCode(() -> {
            limiter.acquireLogin("2.2.2.2");
            limiter.acquireRegister("1.1.1.1");
        }).doesNotThrowAnyException();
    }

    @Test
    void 성공_busy_키에는_10초_만료가_걸린다() {
        at(0, 0);
        limiter.acquireLogin("1.1.1.1");

        assertThat(fake.pttl("shop:rl:login:busy:1.1.1.1")).isEqualTo(10_000);

        at(0, 10); // a crashed request can never hold the slot longer than the TTL
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_로그인_실패_뒤에는_busy가_해제된다() {
        at(0, 0);
        RateLimiter.Attempt attempt = limiter.acquireLogin("1.1.1.1");
        attempt.countAndRelease();

        assertThat(fake.exists("shop:rl:login:busy:1.1.1.1")).isFalse();
    }

    @Test
    void 성공_예외로_끝나도_release하면_busy가_해제된다() {
        at(0, 0);
        RateLimiter.Attempt attempt = limiter.acquireLogin("1.1.1.1");
        attempt.release(); // controller finally after an unexpected exception

        assertThat(fake.exists("shop:rl:login:busy:1.1.1.1")).isFalse();
        assertThat(fake.get("shop:rl:login:count:1.1.1.1")).isNull(); // not counted
    }

    @Test
    void 성공_만료된_뒤_늦게_release해도_다른_요청의_busy를_지우지_않는다() {
        at(0, 0);
        RateLimiter.Attempt slow = limiter.acquireLogin("1.1.1.1");
        at(0, 11); // slow request's busy key expired
        RateLimiter.Attempt next = limiter.acquireLogin("1.1.1.1");

        slow.release();
        slow.countAndRelease();

        assertThat(fake.exists("shop:rl:login:busy:1.1.1.1")).isTrue();
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getMessage()).isEqualTo(BUSY_MESSAGE);
        next.release();
        assertThat(fake.exists("shop:rl:login:busy:1.1.1.1")).isFalse();
    }

    @Test
    void 성공_획득하지_못한_요청은_다른_요청의_busy를_지우지_않는다() {
        limiter.acquireLogin("1.1.1.1");

        blocked(() -> limiter.acquireLogin("1.1.1.1")); // no Attempt is returned, so nothing can be released

        assertThat(fake.exists("shop:rl:login:busy:1.1.1.1")).isTrue();
    }

    @Test
    void 성공_잠금이_확인된_뒤에는_만료까지_Redis_호출이_없다() {
        at(0, 0);
        failLogins(5);
        int calls = fake.scriptCalls;

        at(1, 0);
        for (int i = 0; i < 3; i++) {
            blocked(() -> limiter.acquireLogin("1.1.1.1"));
        }
        assertThat(fake.scriptCalls).isEqualTo(calls);

        at(5, 0);
        limiter.acquireLogin("1.1.1.1").release();
        assertThat(fake.scriptCalls).isGreaterThan(calls);
    }

    @Test
    void 성공_Redis가_죽으면_로컬에서_5회_실패에_5분_잠그고_5분_뒤_풀린다() {
        fake.down = true;
        at(0, 0);
        failLogins(4);
        at(4, 50);
        failLogins(1);

        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
        at(9, 49);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(1);
        at(9, 50);
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis가_죽으면_로컬_회원가입도_5회_뒤_잠긴다() {
        fake.down = true;
        at(0, 0);
        for (int i = 0; i < 5; i++) {
            limiter.acquireRegister("1.1.1.1").countAndRelease();
        }

        assertThat(blocked(() -> limiter.acquireRegister("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
        at(5, 0);
        assertThatCode(() -> limiter.acquireRegister("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis가_죽어도_로컬에서_busy_가드가_동작한다() {
        fake.down = true;
        RateLimiter.Attempt first = limiter.acquireLogin("1.1.1.1");

        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getMessage()).isEqualTo(BUSY_MESSAGE);

        first.release();
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();
    }

    @Test
    void 성공_획득_직후_Redis가_죽어도_실패는_로컬에_남아_5회에_잠긴다() {
        at(0, 0);
        for (int i = 0; i < 5; i++) {
            at(0, i * 11L); // past the 10 s busy TTL left behind by the failed release
            fake.down = false;
            RateLimiter.Attempt attempt = limiter.acquireLogin("1.1.1.1");
            fake.down = true;
            attempt.countAndRelease();
        }

        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
    }

    @Test
    void 성공_로컬_제한기의_메모리는_항목_수_제한을_지킨다() {
        RedisRateLimiter small = new RedisRateLimiter(redis.template, 5, 5, () -> fake.nowMs, 3);
        fake.down = true;

        assertThatCode(() -> {
            for (int i = 0; i < 100; i++) {
                small.acquireLogin("10.0.0." + i).countAndRelease();
            }
        }).doesNotThrowAnyException();

        assertThat(small.fallbackSize()).isLessThanOrEqualTo(3);
    }

    @Test
    void 성공_Redis가_복구되면_다시_Redis_기준으로_판단한다() {
        at(0, 0);
        fake.down = true;
        failLogins(2); // counted locally only
        fake.down = false;

        failLogins(4); // Redis count is 4: not locked even though 6 failures happened in total
        assertThatCode(() -> limiter.acquireLogin("1.1.1.1").release()).doesNotThrowAnyException();

        failLogins(1);
        assertThat(blocked(() -> limiter.acquireLogin("1.1.1.1")).getRetryAfterSeconds()).isEqualTo(300);
    }
}
