package com.shop.backend.domain.error;

/** A seller/admin transition (ship, deliver, refund) that the order's current status does not allow; HTTP 409. */
public class InvalidOrderStateException extends OrderStateException {
    public InvalidOrderStateException(String message) {
        super("INVALID_ORDER_STATE", message);
    }
}
