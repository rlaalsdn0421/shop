package com.shop.backend.infrastructure.ratelimit;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Per-instance limiter with the same rules as the Redis one (N-th attempt in a window locks the IP for a full
 * window counted from that attempt; blocked requests are not counted and do not extend the lock; busy guard).
 * Used only while Redis is unavailable. State is bounded and lost on restart (and not shared across instances).
 */
class InMemoryRateLimiter implements RateLimiter {

    static final long BUSY_MS = 10_000;

    private static final class Entry {
        int count;
        long countExpiresAt;
        long lockedUntil;
        long busyUntil;
        long busyToken;
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final int maxAttempts;
    private final long windowMs;
    private final LongSupplier nowMs;
    private final int maxEntries;
    private long tokenSeq;

    InMemoryRateLimiter(int maxAttempts, long windowMs, LongSupplier nowMs, int maxEntries) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.nowMs = nowMs;
        this.maxEntries = maxEntries;
    }

    @Override
    public Attempt acquireLogin(String ip) {
        return acquire(RedisRateLimiter.LOGIN, ip);
    }

    @Override
    public Attempt acquireRegister(String ip) {
        return acquire(RedisRateLimiter.REGISTER, ip);
    }

    Attempt acquire(String kind, String ip) {
        String key = kind + ":" + ip;
        long token;
        synchronized (this) {
            long now = nowMs.getAsLong();
            Entry e = entries.computeIfAbsent(key, k -> new Entry());
            evictIfFull(entries, maxEntries, x -> isDead(x, now), key);
            if (e.lockedUntil > now) {
                throw new TooManyRequestsException(ceilSeconds(e.lockedUntil - now));
            }
            if (e.busyUntil > now) {
                throw TooManyRequestsException.busy();
            }
            token = ++tokenSeq;
            e.busyToken = token;
            e.busyUntil = now + BUSY_MS;
        }
        return new LocalAttempt(key, token);
    }

    /** Counts one attempt without touching the busy slot (used when Redis failed after acquiring). */
    synchronized void recordCount(String kind, String ip) {
        long now = nowMs.getAsLong();
        String key = kind + ":" + ip;
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        evictIfFull(entries, maxEntries, x -> isDead(x, now), key);
        if (e.count == 0 || e.countExpiresAt <= now) {
            e.count = 0;
            e.countExpiresAt = now + windowMs;
        }
        e.count++;
        if (e.count >= maxAttempts) {
            e.lockedUntil = now + windowMs;
            e.count = 0;
            e.countExpiresAt = 0;
        }
    }

    synchronized int size() {
        return entries.size();
    }

    private synchronized void releaseSlot(String key, long token) {
        Entry e = entries.get(key);
        if (e != null && e.busyToken == token) {
            e.busyUntil = 0;
        }
    }

    private static boolean isDead(Entry e, long now) {
        return e.lockedUntil <= now && e.busyUntil <= now && (e.count == 0 || e.countExpiresAt <= now);
    }

    static long ceilSeconds(long ms) {
        return Math.max(1, (ms + 999) / 1000);
    }

    /** Keeps the map bounded: when over capacity drop expired entries first, then the oldest ones. */
    static <V> void evictIfFull(Map<String, V> map, int max, Predicate<V> dead, String keep) {
        if (map.size() <= max) {
            return;
        }
        map.entrySet().removeIf(en -> !en.getKey().equals(keep) && dead.test(en.getValue()));
        var it = map.entrySet().iterator();
        while (map.size() > max && it.hasNext()) {
            if (!it.next().getKey().equals(keep)) {
                it.remove();
            }
        }
    }

    private final class LocalAttempt implements Attempt {
        private final String key;
        private final long token;
        private final AtomicBoolean done = new AtomicBoolean();

        LocalAttempt(String key, long token) {
            this.key = key;
            this.token = token;
        }

        @Override
        public void countAndRelease() {
            if (done.compareAndSet(false, true)) {
                int split = key.indexOf(':');
                recordCount(key.substring(0, split), key.substring(split + 1));
                releaseSlot(key, token);
            }
        }

        @Override
        public void release() {
            if (done.compareAndSet(false, true)) {
                releaseSlot(key, token);
            }
        }
    }
}
