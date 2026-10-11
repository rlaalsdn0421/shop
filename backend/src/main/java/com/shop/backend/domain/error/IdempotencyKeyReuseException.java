package com.shop.backend.domain.error;

/** Same Idempotency-Key sent with a different order body; mapped to HTTP 409 at the presentation layer. */
public class IdempotencyKeyReuseException extends DomainException {
    public IdempotencyKeyReuseException() {
        super("같은 요청 번호로 내용이 다른 주문은 보낼 수 없어요.");
    }
}
