package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque keyset position in "my orders": base64url(createdAt ISO + "|" + id). The client may send anything, so
 * decoding never trusts the format. createdAt must come from the value read back from the database (microseconds),
 * otherwise the next page's "created_at = :c" comparison could miss the boundary row.
 */
public record OrderCursor(Instant createdAt, String id) {

    private static final int MAX_LENGTH = 200;
    // Order ids are UUIDs; anything else (NUL, control characters, '|', non-ASCII) never matches a row and a NUL
    // would even make PostgreSQL fail with a 500, so it is rejected as a bad cursor up front.
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,36}");
    // Reasonable bounds, well inside PostgreSQL's timestamp range, so an extreme year cannot reach the database.
    private static final Instant MIN_CREATED_AT = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX_CREATED_AT = Instant.parse("9999-01-01T00:00:00Z");

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static OrderCursor decode(String cursor) {
        try {
            if (cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
                throw new IllegalArgumentException();
            }
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar < 0 || !ID.matcher(raw.substring(bar + 1)).matches()) {
                throw new IllegalArgumentException();
            }
            Instant createdAt = Instant.parse(raw.substring(0, bar));
            if (createdAt.isBefore(MIN_CREATED_AT) || createdAt.isAfter(MAX_CREATED_AT)) {
                throw new IllegalArgumentException();
            }
            return new OrderCursor(createdAt, raw.substring(bar + 1));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new ValidationException("커서가 올바르지 않아요.");
        }
    }
}
