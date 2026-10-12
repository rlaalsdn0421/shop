package com.shop.backend.domain.entity;

/** Confirmed shipping policy. Change together with the frontend constants (frontend/src/lib/constants.ts). */
public final class ShippingPolicy {

    public static final int FEE = 3_500;
    public static final int FREE_THRESHOLD = 50_000;

    private ShippingPolicy() {
    }

    /** Fee for an order whose items add up to {@code itemsAmount}: free from the threshold, otherwise {@link #FEE}. */
    public static int feeFor(int itemsAmount) {
        return itemsAmount >= FREE_THRESHOLD ? 0 : FEE;
    }
}
