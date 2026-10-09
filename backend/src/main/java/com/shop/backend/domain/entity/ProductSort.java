package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

/** Sort orders of the product list; {@code key} is the value of the {@code sort} query parameter. */
public enum ProductSort {
    NEWEST("newest"),
    PRICE_ASC("price_asc"),
    PRICE_DESC("price_desc"),
    REVIEWS("reviews"),
    RATING("rating"),
    SALES("sales"),
    POPULAR("popular");

    private final String key;

    ProductSort(String key) {
        this.key = key;
    }

    /** Missing/blank means the default (newest); any other unknown value is rejected. */
    public static ProductSort parse(String value) {
        if (value == null || value.isBlank()) {
            return NEWEST;
        }
        String trimmed = value.trim();
        for (ProductSort sort : values()) {
            if (sort.key.equals(trimmed)) {
                return sort;
            }
        }
        throw new ValidationException("지원하지 않는 정렬이에요.");
    }
}
