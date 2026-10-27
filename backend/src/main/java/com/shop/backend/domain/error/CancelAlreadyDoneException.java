package com.shop.backend.domain.error;

/** The seller tried to abandon a cancel that the provider already carried out; HTTP 409, "check the cancel status" instead. */
public class CancelAlreadyDoneException extends OrderStateException {
    public CancelAlreadyDoneException() {
        super("CANCEL_ALREADY_DONE", "결제사에서 이미 취소됐어요. '취소 상태 확인'을 눌러 주문을 마무리해 주세요.");
    }
}
