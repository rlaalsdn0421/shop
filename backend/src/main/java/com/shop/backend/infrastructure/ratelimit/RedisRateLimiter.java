package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Redis-backed limiter. Per kind and IP there are three keys: a counter (expires one window after the first
 * attempt), a lock (set for a full window when the max-attempts-th attempt is counted, then the counter resets)
 * and a short "busy" key that serializes attempts from one IP.
 *
 * <ul>
 *   <li>acquire: local lock cache (no Redis call while a known lock is active) -> ONE Lua script that checks
 *       the lock and takes the busy key (SET NX, 10 s TTL, value = per-attempt token).</li>
 *   <li>finish: countAndRelease = ONE Lua script (count + maybe lock + compare-and-delete busy);
 *       release = ONE Lua script (compare-and-delete busy). A request never deletes a busy key it does not own.</li>
 *   <li>Redis error (down, timeout, quota): the same rules run on a bounded per-instance in-memory limiter.
 *       One WARN per outage (no IP, no credentials). The limiter never throws except for 429.</li>
 * </ul>
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimiter.class);

    static final String LOGIN = "login";
    static final String REGISTER = "register";
    static final String BUSY_TTL_SECONDS = "10";
    static final int DEFAULT_MAX_LOCAL_ENTRIES = 10_000;

    /**
     * KEYS[1]=lock, KEYS[2]=busy; ARGV[1]=busy TTL seconds, ARGV[2]=token.
     * Returns remaining lock ms (>0) if locked, -1 if busy, 0 if acquired. A lock without TTL is deleted.
     */
    static final String ACQUIRE_SCRIPT = String.join("\n",
            "local ttl = redis.call('PTTL', KEYS[1])",
            "if ttl > 0 then return ttl end",
            "if ttl == -1 then redis.call('DEL', KEYS[1]) end",
            "if redis.call('SET', KEYS[2], ARGV[2], 'NX', 'EX', ARGV[1]) then return 0 end",
            "return -1");

    /**
     * KEYS[1]=count, KEYS[2]=lock, KEYS[3]=busy; ARGV[1]=window seconds, ARGV[2]=max attempts, ARGV[3]=token.
     * Returns 1 if this attempt created the lock, else 0. Releases the busy key only if it holds our token.
     */
    static final String COUNT_RELEASE_SCRIPT = String.join("\n",
            "local c = redis.call('INCR', KEYS[1])",
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end",
            "local locked = 0",
            "if c >= tonumber(ARGV[2]) then",
            "  redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])",
            "  redis.call('DEL', KEYS[1])",
            "  locked = 1",
            "end",
            "if redis.call('GET', KEYS[3]) == ARGV[3] then redis.call('DEL', KEYS[3]) end",
            "return locked");

    /** KEYS[1]=busy; ARGV[1]=token. Deletes the busy key only if it holds our token. */
    static final String RELEASE_SCRIPT = String.join("\n",
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end",
            "return 0");

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(ACQUIRE_SCRIPT, Long.class);
    private static final DefaultRedisScript<Long> COUNT_RELEASE =
            new DefaultRedisScript<>(COUNT_RELEASE_SCRIPT, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);

    private final StringRedisTemplate redis;
    private final String maxAttempts;
    private final String windowSeconds;
    private final long windowMs;
    private final LongSupplier nowMs;
    private final int maxLocalEntries;
    private final InMemoryRateLimiter fallback;
    private final Map<String, Long> lockCache = new LinkedHashMap<>(); // key -> lockedUntil ms, guarded by itself
    private final AtomicBoolean degraded = new AtomicBoolean();

    @Autowired
    public RedisRateLimiter(StringRedisTemplate redis,
                            @Value("${rate-limit.max-attempts}") int maxAttempts,
                            @Value("${rate-limit.window-minutes}") long windowMinutes) {
        this(redis, maxAttempts, windowMinutes, System::currentTimeMillis, DEFAULT_MAX_LOCAL_ENTRIES);
    }

    RedisRateLimiter(StringRedisTemplate redis, int maxAttempts, long windowMinutes,
                     LongSupplier nowMs, int maxLocalEntries) {
        this.redis = redis;
        this.maxAttempts = String.valueOf(maxAttempts);
        this.windowSeconds = String.valueOf(windowMinutes * 60);
        this.windowMs = windowMinutes * 60_000;
        this.nowMs = nowMs;
        this.maxLocalEntries = maxLocalEntries;
        this.fallback = new InMemoryRateLimiter(maxAttempts, windowMs, nowMs, maxLocalEntries);
    }

    @Override
    public Attempt acquireLogin(String ip) {
        return acquire(LOGIN, ip);
    }

    @Override
    public Attempt acquireRegister(String ip) {
        return acquire(REGISTER, ip);
    }

    int lockCacheSize() {
        synchronized (lockCache) {
            return lockCache.size();
        }
    }

    int fallbackSize() {
        return fallback.size();
    }

    private static String countKey(String kind, String ip) {
        return "shop:rl:" + kind + ":count:" + ip;
    }

    private static String lockKey(String kind, String ip) {
        return "shop:rl:" + kind + ":lock:" + ip;
    }

    private static String busyKey(String kind, String ip) {
        return "shop:rl:" + kind + ":busy:" + ip;
    }

    private Attempt acquire(String kind, String ip) {
        String cacheKey = kind + ":" + ip;
        long now = nowMs.getAsLong();
        Long cachedUntil;
        synchronized (lockCache) {
            cachedUntil = lockCache.get(cacheKey);
            if (cachedUntil != null && cachedUntil <= now) {
                lockCache.remove(cacheKey);
                cachedUntil = null;
            }
        }
        if (cachedUntil != null) {
            throw new TooManyRequestsException(InMemoryRateLimiter.ceilSeconds(cachedUntil - now));
        }

        String token = UUID.randomUUID().toString();
        Long result;
        try {
            result = redis.execute(ACQUIRE, List.of(lockKey(kind, ip), busyKey(kind, ip)),
                    BUSY_TTL_SECONDS, token);
            if (result == null) {
                throw new IllegalStateException("empty script result");
            }
            markUp();
        } catch (RuntimeException ex) {
            markDown(ex);
            return fallback.acquire(kind, ip);
        }
        if (result > 0) {
            cacheLock(cacheKey, now + result);
            throw new TooManyRequestsException(InMemoryRateLimiter.ceilSeconds(result));
        }
        if (result < 0) {
            throw TooManyRequestsException.busy();
        }
        return new RedisAttempt(kind, ip, token);
    }

    private void cacheLock(String cacheKey, long untilMs) {
        long now = nowMs.getAsLong();
        synchronized (lockCache) {
            lockCache.put(cacheKey, untilMs);
            InMemoryRateLimiter.evictIfFull(lockCache, maxLocalEntries, until -> until <= now, cacheKey);
        }
    }

    private void markDown(RuntimeException ex) {
        if (degraded.compareAndSet(false, true)) {
            logger.warn("Redis rate limiter unavailable, using per-instance in-memory fallback: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private void markUp() {
        if (degraded.compareAndSet(true, false)) {
            logger.info("Redis rate limiter recovered, leaving in-memory fallback");
        }
    }

    private final class RedisAttempt implements Attempt {
        private final String kind;
        private final String ip;
        private final String token;
        private final AtomicBoolean done = new AtomicBoolean();

        RedisAttempt(String kind, String ip, String token) {
            this.kind = kind;
            this.ip = ip;
            this.token = token;
        }

        @Override
        public void countAndRelease() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            try {
                Long locked = redis.execute(COUNT_RELEASE,
                        List.of(countKey(kind, ip), lockKey(kind, ip), busyKey(kind, ip)),
                        windowSeconds, maxAttempts, token);
                if (locked == null) {
                    throw new IllegalStateException("empty script result");
                }
                markUp();
                if (locked == 1L) {
                    cacheLock(kind + ":" + ip, nowMs.getAsLong() + windowMs);
                }
            } catch (RuntimeException ex) {
                markDown(ex);
                fallback.recordCount(kind, ip); // do not lose the failure; busy key expires by its own TTL
            }
        }

        @Override
        public void release() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            try {
                redis.execute(RELEASE, List.of(busyKey(kind, ip)), token);
                markUp();
            } catch (RuntimeException ex) {
                markDown(ex); // busy key expires by its own 10 s TTL
            }
        }
    }
}
