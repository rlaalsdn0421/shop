package com.shop.backend.domain.error;

/** The provider's payment does not match the order (amount, currency, id or store); mapped to HTTP 409. */
public class PaymentAmountMismatchException extends PaymentException {
    public PaymentAmountMismatchException() {
        super("PAYMENT_AMOUNT_MISMATCH", "결제 정보가 주문과 일치하지 않아 확인할 수 없어요. 고객센터에 문의해 주세요.");
    }
}
