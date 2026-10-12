package com.shop.backend.domain.error;

/** Payment settings (API secret / store id) are empty; mapped to HTTP 503. */
public class PaymentNotConfiguredException extends PaymentException {
    public PaymentNotConfiguredException() {
        super("PAYMENT_NOT_CONFIGURED", "결제 기능이 아직 준비되지 않았어요.");
    }
}
