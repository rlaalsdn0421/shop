package com.shop.backend.domain.error;

/** Payment failures that carry a machine-readable code for the client; the HTTP status is chosen at the presentation layer. */
public abstract class PaymentException extends DomainException {
    private final String code;

    protected PaymentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
