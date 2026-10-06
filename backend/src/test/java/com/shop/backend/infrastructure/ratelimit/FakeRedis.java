package com.shop.backend.infrastructure.ratelimit;

import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java model of the Redis commands the three Lua scripts use (PTTL, SET [NX] EX, INCR, EXPIRE, GET, DEL)
 * with a controllable clock. Each script handler mirrors the Lua text step by step; it checks the intended
 * algorithm, NOT the Lua syntax.
 */
final class FakeRedis implements ScriptedRedis.Handler {

    long nowMs = 0;
    boolean down = false;
    int scriptCalls = 0;
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

    boolean exists(String key) {
        purge();
        return values.containsKey(key);
    }

    String get(String key) {
        purge();
        return values.get(key);
    }

    /** Redis PTTL: -2 missing, -1 no expiry, else remaining ms. */
    long pttl(String key) {
        purge();
        if (!values.containsKey(key)) {
            return -2;
        }
        return expiresAt.containsKey(key) ? expiresAt.get(key) - nowMs : -1;
    }

    private long incr(String key) {
        long v = values.containsKey(key) ? Long.parseLong(values.get(key)) + 1 : 1;
        values.put(key, String.valueOf(v));
        return v;
    }

    private void expire(String key, long seconds) {
        expiresAt.put(key, nowMs + seconds * 1000);
    }

    private void setEx(String key, String value, long seconds) {
        values.put(key, value);
        expire(key, seconds);
    }

    private void del(String key) {
        values.remove(key);
        expiresAt.remove(key);
    }

    @Override
    public Object handle(String script, List<String> k, List<String> a) {
        scriptCalls++;
        if (down) {
            throw new RedisConnectionFailureException("down");
        }
        purge();
        if (script.equals(RedisRateLimiter.ACQUIRE_SCRIPT)) {
            long ttl = pttl(k.get(0));
            if (ttl > 0) {
                return ttl;
            }
            if (ttl == -1) {
                del(k.get(0));
            }
            if (!values.containsKey(k.get(1))) { // SET NX EX
                setEx(k.get(1), a.get(1), Long.parseLong(a.get(0)));
                return 0L;
            }
            return -1L;
        }
        if (script.equals(RedisRateLimiter.COUNT_RELEASE_SCRIPT)) {
            long c = incr(k.get(0));
            if (c == 1) {
                expire(k.get(0), Long.parseLong(a.get(0)));
            }
            long locked = 0;
            if (c >= Long.parseLong(a.get(1))) {
                setEx(k.get(1), "1", Long.parseLong(a.get(0)));
                del(k.get(0));
                locked = 1;
            }
            if (a.get(2).equals(values.get(k.get(2)))) {
                del(k.get(2));
            }
            return locked;
        }
        if (script.equals(RedisRateLimiter.RELEASE_SCRIPT)) {
            if (a.get(0).equals(values.get(k.get(0)))) {
                del(k.get(0));
                return 1L;
            }
            return 0L;
        }
        throw new IllegalArgumentException("unknown script");
    }
}
