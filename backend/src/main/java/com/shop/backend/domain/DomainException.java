package com.shop.backend.domain;

/** Base type for business-rule violations; mapped to HTTP 400 at the presentation layer. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
