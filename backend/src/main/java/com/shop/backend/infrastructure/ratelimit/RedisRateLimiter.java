package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed limiter. Per kind and IP: a counter (expires after one window from the first attempt)
 * and a lock. The max-attempts-th attempt creates the lock for a full window counted from that attempt and
 * resets the counter; while locked, requests are rejected, not counted, and do not extend the lock.
 * Redis errors fail open (warning without IP/credentials) so the limiter can never break auth.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiter.class);

    static final String LOGIN = "login";
    static final String REGISTER = "register";

    // Atomic: a crash can never leave a counter without TTL or a lock without expiry.
    static final String COUNT_SCRIPT = "local c = redis.call('INCR', KEYS[1]); "
            + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; "
            + "if c >= tonumber(ARGV[2]) then "
            + "redis.call('SET', KEYS[2], '1', 'EX', ARGV[1]); "
            + "redis.call('DEL', KEYS[1]); "
            + "return 1 end; "
            + "return 0";

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(COUNT_SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final String maxAttempts;
    private final String windowSeconds;

    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${rate-limit.max-attempts}") int maxAttempts,
                            @Value("${rate-limit.window-minutes}") long windowMinutes) {
        this.redis = redis;
        this.maxAttempts = String.valueOf(maxAttempts);
        this.windowSeconds = String.valueOf(windowMinutes * 60);
    }

    @Override
    public void assertLoginAllowed(String ip) {
        assertNotLocked(LOGIN, ip);
    }

    @Override
    public void recordLoginFailure(String ip) {
        count(LOGIN, ip);
    }

    @Override
    public void checkAndRecordRegister(String ip) {
        assertNotLocked(REGISTER, ip);
        count(REGISTER, ip);
    }

    private static String countKey(String kind, String ip) {
        return "shop:rl:" + kind + ":count:" + ip;
    }

    private static String lockKey(String kind, String ip) {
        return "shop:rl:" + kind + ":lock:" + ip;
    }

    private void assertNotLocked(String kind, String ip) {
        Long remainingMs;
        try {
            String key = lockKey(kind, ip);
            remainingMs = redis.getExpire(key, TimeUnit.MILLISECONDS);
            if (remainingMs != null && remainingMs == -1) {
                // a lock without expiry must never block forever
                redis.delete(key);
                return;
            }
        } catch (RuntimeException ex) {
            logger.warn("Rate-limit lock check failed, allowing request: {}", ex.getClass().getSimpleName());
            return;
        }
        if (remainingMs != null && remainingMs > 0) {
            throw new TooManyRequestsException(Math.max(1, (remainingMs + 999) / 1000));
        }
    }

    private void count(String kind, String ip) {
        try {
            redis.execute(SCRIPT, List.of(countKey(kind, ip), lockKey(kind, ip)), windowSeconds, maxAttempts);
        } catch (RuntimeException ex) {
            logger.warn("Rate-limit count failed, allowing request: {}", ex.getClass().getSimpleName());
        }
    }
}
