package com.shop.backend.domain.error;

/** The customer cannot cancel this order (shipped already, or not paid); HTTP 409. */
public class OrderNotCancellableException extends OrderStateException {
    public OrderNotCancellableException(String message) {
        super("ORDER_NOT_CANCELLABLE", message);
    }
}
