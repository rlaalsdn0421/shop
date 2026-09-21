package com.shop.backend.domain.error;

public class ValidationException extends DomainException {
    public ValidationException(String message) {
        super(message);
    }
}
