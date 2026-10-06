package com.shop.backend.application.service;

/** Per-IP limiter for auth endpoints. Implementations throw TooManyRequestsException when blocked. */
public interface RateLimiter {

    /** Throws if the IP already has too many failed logins in the window. Does not record anything. */
    void assertLoginAllowed(String ip);

    /** Records one failed login for the IP. */
    void recordLoginFailure(String ip);

    /** Throws if the IP already has too many register requests in the window; otherwise records this one. */
    void checkAndRecordRegister(String ip);
}
