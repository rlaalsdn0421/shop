package com.shop.backend.presentation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code code} (machine-readable, e.g. PAYMENT_NOT_CONFIRMED) is only present for the errors that define one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String code) {
    public ErrorResponse(String error) {
        this(error, null);
    }
}
