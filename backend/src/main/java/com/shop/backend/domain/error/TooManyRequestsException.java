package com.shop.backend.domain.error;

/** Rate limit exceeded; mapped to HTTP 429 with a Retry-After header at the presentation layer. */
public class TooManyRequestsException extends DomainException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("시도 횟수를 초과했습니다. " + Math.max(1, (retryAfterSeconds + 59) / 60) + "분 후에 다시 시도해 주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
