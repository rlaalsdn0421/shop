package com.shop.backend.domain.error;

/** The seller tried to abandon a cancel while the provider does not report this order's payment as PAID; HTTP 409. */
public class CancelProviderStateException extends OrderStateException {
    /** The provider has no payment for the order; not reverted, a 404 can also mean a wrong API base or another store's secret. */
    public CancelProviderStateException() {
        super("CANCEL_PROVIDER_STATE", "결제사에 이 주문의 결제 정보가 없어요. PortOne 콘솔에서 확인해 주세요.");
    }

    public CancelProviderStateException(String providerStatus) {
        super("CANCEL_PROVIDER_STATE", "결제사의 결제 상태가 " + providerStatus
                + "이라 취소를 포기할 수 없어요. PortOne 콘솔에서 결제 상태를 직접 확인해 주세요.");
    }
}
