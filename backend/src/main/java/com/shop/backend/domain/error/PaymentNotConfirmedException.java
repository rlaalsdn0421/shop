package com.shop.backend.domain.error;

/** The provider does not (yet) report this order as paid; mapped to HTTP 409. */
public class PaymentNotConfirmedException extends PaymentException {
    public PaymentNotConfirmedException() {
        super("PAYMENT_NOT_CONFIRMED", "아직 결제가 완료되지 않았어요. 결제를 마친 뒤 다시 확인해 주세요.");
    }
}
