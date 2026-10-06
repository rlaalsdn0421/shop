package com.shop.backend.domain.error;

/** Rate limit exceeded; mapped to HTTP 429 with a Retry-After header at the presentation layer. */
public class TooManyRequestsException extends DomainException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        this(retryAfterSeconds,
                "시도 횟수를 초과했습니다. " + Math.max(1, (retryAfterSeconds + 59) / 60) + "분 후에 다시 시도해 주세요.");
    }

    private TooManyRequestsException(long retryAfterSeconds, String message) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Another attempt from the same IP is still being processed. */
    public static TooManyRequestsException busy() {
        return new TooManyRequestsException(1, "요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
