package com.shop.backend.domain.error;

/** Missing order, or one that belongs to someone else (indistinguishable on purpose); mapped to HTTP 404. */
public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException() {
        super("주문을 찾을 수 없어요.");
    }
}
