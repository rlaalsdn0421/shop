package com.shop.backend.domain.entity;

import java.util.Comparator;

/**
 * Identifies one row whose stock is updated: a product (legacy, {@code optionId} null) or one of its options. EVERY path
 * that updates several stock rows in a loop (order creation, expiry, cancel, late-payment re-reservation and its
 * rollback, replay expiry) must walk them in this natural order: each update row-locks its row until commit, so a single
 * global order keeps concurrent orders (even legacy + option lines mixed) from deadlocking. A product is either legacy
 * or has options, so (productId, optionId-or-"") orders every lockable row without ties across the two tables.
 * A blank optionId is the same as none, so the order key never sees a "" that is not "no option".
 */
public record StockKey(String productId, String optionId) implements Comparable<StockKey> {

    private static final Comparator<StockKey> ORDER = Comparator
            .comparing(StockKey::productId)
            .thenComparing(key -> key.optionId() == null ? "" : key.optionId());

    public StockKey {
        optionId = optionId == null || optionId.isBlank() ? null : optionId;
    }

    @Override
    public int compareTo(StockKey other) {
        return ORDER.compare(this, other);
    }
}
