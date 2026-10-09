package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.time.Duration;

/** Sales window of the best-products section. */
public enum BestPeriod {
    REALTIME("realtime", Duration.ofHours(24)),
    WEEKLY("weekly", Duration.ofDays(7)),
    MONTHLY("monthly", Duration.ofDays(30));

    private final String key;
    private final Duration window;

    BestPeriod(String key, Duration window) {
        this.key = key;
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    /** Missing/blank means realtime; any other unknown value is rejected. */
    public static BestPeriod parse(String value) {
        if (value == null || value.isBlank()) {
            return REALTIME;
        }
        String trimmed = value.trim();
        for (BestPeriod period : values()) {
            if (period.key.equals(trimmed)) {
                return period;
            }
        }
        throw new ValidationException("지원하지 않는 기간이에요.");
    }
}
