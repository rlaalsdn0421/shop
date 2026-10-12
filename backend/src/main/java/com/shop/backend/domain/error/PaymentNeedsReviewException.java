package com.shop.backend.domain.error;

/** Paid after the order expired and the stock is gone; an operator cancels it at the provider. HTTP 409. */
public class PaymentNeedsReviewException extends PaymentException {
    public PaymentNeedsReviewException() {
        super("PAYMENT_NEEDS_REVIEW", "주문이 만료된 뒤 결제되어 재고를 다시 잡지 못했어요. 확인 후 취소·환불을 안내해 드릴게요.");
    }
}
