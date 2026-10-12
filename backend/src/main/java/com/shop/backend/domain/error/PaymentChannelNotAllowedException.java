package com.shop.backend.domain.error;

/** The provider payment was made on a channel we do not accept (e.g. a TEST channel in production); HTTP 409. */
public class PaymentChannelNotAllowedException extends PaymentException {
    public PaymentChannelNotAllowedException() {
        super("PAYMENT_CHANNEL_NOT_ALLOWED", "허용되지 않은 결제 채널이라 결제를 확인할 수 없어요. 고객센터에 문의해 주세요.");
    }
}
