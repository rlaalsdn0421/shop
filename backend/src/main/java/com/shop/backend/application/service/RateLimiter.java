package com.shop.backend.application.service;

/**
 * Per-IP limiter for auth endpoints. Acquiring atomically checks the lock and reserves a short per-IP,
 * per-kind "busy" slot, so credential checks from one IP are serialized. Implementations throw
 * TooManyRequestsException when the IP is locked or already busy, and never fail for storage errors.
 */
public interface RateLimiter {

    /** Throws if the IP is locked (too many failed logins) or busy; otherwise reserves the slot. */
    Attempt acquireLogin(String ip);

    /** Throws if the IP is locked (too many register requests) or busy; otherwise reserves the slot. */
    Attempt acquireRegister(String ip);

    /** Handle for one reserved attempt. Always finish it: countAndRelease() or release() (idempotent). */
    interface Attempt {

        /** Counts this attempt toward the limit (may create the lock) and releases the slot. */
        void countAndRelease();

        /** Releases the slot without counting. No-op if already finished. */
        void release();
    }
}
