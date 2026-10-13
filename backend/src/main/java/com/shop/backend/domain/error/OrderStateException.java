package com.shop.backend.domain.error;

/** An order action that the order's current status does not allow; carries a machine-readable code, HTTP 409. */
public abstract class OrderStateException extends DomainException {
    private final String code;

    protected OrderStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
