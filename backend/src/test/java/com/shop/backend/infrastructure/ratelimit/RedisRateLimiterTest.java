package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.domain.error.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mocks StringRedisTemplate: checks which Redis calls are made, not the Lua text (needs a real Redis). */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redis, 5, 5);

    private void lockTtl(String key, Long ttlMs) {
        when(redis.getExpire(key, TimeUnit.MILLISECONDS)).thenReturn(ttlMs);
    }

    private void assertBlocked(Runnable call, long retrySeconds, String minutes) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
            assertThat(ex.getRetryAfterSeconds()).isEqualTo(retrySeconds);
            assertThat(ex.getMessage()).isEqualTo("시도 횟수를 초과했습니다. " + minutes + "분 후에 다시 시도해 주세요.");
        });
    }

    @Test
    void 실패_잠금이_있으면_429이고_남은_시간을_초로_올림한다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", 299_500L);

        assertBlocked(() -> limiter.assertLoginAllowed("1.1.1.1"), 300, "5");
    }

    @Test
    void 실패_남은_시간이_1ms여도_최소_1초_1분이다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", 1L);

        assertBlocked(() -> limiter.assertLoginAllowed("1.1.1.1"), 1, "1");
    }

    @Test
    void 실패_분은_올림한다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", 61_000L);

        assertBlocked(() -> limiter.assertLoginAllowed("1.1.1.1"), 61, "2");
    }

    @Test
    void 성공_잠금이_없으면_로그인이_허용된다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", -2L);

        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void 성공_남은_시간이_0이거나_null이면_허용된다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", 0L);
        lockTtl("shop:rl:login:lock:2.2.2.2", null);

        assertThatCode(() -> {
            limiter.assertLoginAllowed("1.1.1.1");
            limiter.assertLoginAllowed("2.2.2.2");
        }).doesNotThrowAnyException();
    }

    @Test
    void 성공_만료시간이_없는_잠금은_삭제하고_허용한다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", -1L);

        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
        verify(redis).delete("shop:rl:login:lock:1.1.1.1");
    }

    @Test
    void 성공_로그인_실패는_예상한_키와_인자로_스크립트를_실행한다() {
        limiter.recordLoginFailure("1.1.1.1");

        ArgumentCaptor<RedisScript> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(),
                eq(List.of("shop:rl:login:count:1.1.1.1", "shop:rl:login:lock:1.1.1.1")), eq("300"), eq("5"));
        assertThat(script.getValue().getScriptAsString()).isEqualTo(RedisRateLimiter.COUNT_SCRIPT);
        assertThat(script.getValue().getResultType()).isEqualTo(Long.class);
    }

    @Test
    void 성공_IP가_다르면_키가_다르다() {
        limiter.recordLoginFailure("1.1.1.1");
        limiter.recordLoginFailure("2.2.2.2");

        verify(redis).execute(any(RedisScript.class),
                eq(List.of("shop:rl:login:count:1.1.1.1", "shop:rl:login:lock:1.1.1.1")), eq("300"), eq("5"));
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("shop:rl:login:count:2.2.2.2", "shop:rl:login:lock:2.2.2.2")), eq("300"), eq("5"));
    }

    @Test
    void 성공_회원가입은_로그인과_다른_키를_쓰고_잠금을_먼저_확인한_뒤_센다() {
        lockTtl("shop:rl:register:lock:1.1.1.1", -2L);

        limiter.checkAndRecordRegister("1.1.1.1");

        InOrder order = inOrder(redis);
        order.verify(redis).getExpire("shop:rl:register:lock:1.1.1.1", TimeUnit.MILLISECONDS);
        order.verify(redis).execute(any(RedisScript.class),
                eq(List.of("shop:rl:register:count:1.1.1.1", "shop:rl:register:lock:1.1.1.1")),
                eq("300"), eq("5"));
    }

    @Test
    void 실패_잠긴_회원가입은_429이고_카운트_스크립트를_실행하지_않는다() {
        lockTtl("shop:rl:register:lock:1.1.1.1", 120_000L);

        assertBlocked(() -> limiter.checkAndRecordRegister("1.1.1.1"), 120, "2");
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void 성공_로그인_잠금_확인은_스크립트를_실행하지_않는다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", 10_000L);

        assertThatThrownBy(() -> limiter.assertLoginAllowed("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void 성공_Redis_장애면_잠금_확인은_통과한다() {
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis_장애면_만료시간_없는_잠금_삭제_실패도_통과한다() {
        lockTtl("shop:rl:login:lock:1.1.1.1", -1L);
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());

        assertThatCode(() -> limiter.assertLoginAllowed("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis_장애면_실패_기록은_예외_없이_통과한다() {
        doThrow(new RedisConnectionFailureException("down")).when(redis)
                .execute(any(RedisScript.class), anyList(), eq("300"), eq("5"));

        assertThatCode(() -> limiter.recordLoginFailure("1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void 성공_Redis_장애면_회원가입_확인과_기록도_통과한다() {
        when(redis.getExpire(anyString(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RedisConnectionFailureException("down"));
        doThrow(new RedisConnectionFailureException("down")).when(redis)
                .execute(any(RedisScript.class), anyList(), eq("300"), eq("5"));

        assertThatCode(() -> limiter.checkAndRecordRegister("1.1.1.1")).doesNotThrowAnyException();
    }
}
