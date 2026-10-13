package com.shop.backend.domain.error;

/**
 * The payment provider failed or timed out while cancelling a payment; HTTP 503. {@code outcomeUnknown} is true when
 * no answer arrived (timeout / connection error): the cancel may still have gone through, so the order stays CANCELING.
 * False means the provider answered with an error, i.e. nothing was cancelled.
 */
public class CancelProviderUnavailableException extends PaymentException {
    private final boolean outcomeUnknown;

    public CancelProviderUnavailableException(boolean outcomeUnknown) {
        super("CANCEL_PROVIDER_UNAVAILABLE", "취소 처리가 지연되고 있어요. 잠시 뒤 다시 시도해 주세요.");
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
