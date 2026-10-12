package com.shop.backend.domain.error;

/** The payment provider failed or timed out; mapped to HTTP 503. */
public class PaymentProviderUnavailableException extends PaymentException {
    public PaymentProviderUnavailableException() {
        super("PAYMENT_PROVIDER_UNAVAILABLE", "결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요.");
    }
}
