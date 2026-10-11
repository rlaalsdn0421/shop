package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Business-rule validation for order placement, mirroring the storefront's original rules. */
public final class OrderValidation {

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private OrderValidation() {
    }

    public static void validateIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ValidationException("요청 번호(Idempotency-Key)가 올바르지 않아요.");
        }
    }

    /**
     * SHA-256 hex of the order content (customer fields trimmed, quantities per product sorted by id), so a retry
     * of the same order matches but a different order under the same key does not. Fields are length-prefixed so
     * no value can imitate a separator.
     */
    public static String requestHash(String customerName, String customerPhone, String customerAddress,
                                     Map<String, Integer> quantityByProductId) {
        StringBuilder canonical = new StringBuilder();
        for (String field : List.of(customerName.trim(), customerPhone.trim(), customerAddress.trim())) {
            canonical.append(field.length()).append(':').append(field).append('\n');
        }
        new TreeMap<>(quantityByProductId).forEach((id, qty) ->
                canonical.append(id.length()).append(':').append(id).append('=').append(qty).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory in every JRE
        }
    }

    public static void validateCustomerInfo(String customerName, String customerPhone, String customerAddress) {
        if (isBlank(customerName) || isBlank(customerPhone) || isBlank(customerAddress)
                || customerName.length() > 200 || customerPhone.length() > 50 || customerAddress.length() > 500) {
            throw new ValidationException("필수 정보가 누락되었거나 형식이 올바르지 않습니다.");
        }
    }

    public static void validateOrderLines(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new ValidationException("주문할 상품이 없습니다.");
        }
        if (lines.stream().anyMatch(line -> line == null || isBlank(line.productId()))) {
            throw new ValidationException("상품 정보가 올바르지 않습니다.");
        }
        boolean hasInvalidQuantity = lines.stream()
                .anyMatch(line -> line.quantity() == null || line.quantity() <= 0);
        if (hasInvalidQuantity) {
            throw new ValidationException("수량이 올바르지 않습니다.");
        }
    }

    /**
     * Aggregates duplicate productIds so stock is checked against the total requested,
     * not per line item — otherwise repeated lines for one product bypass the stock check
     * (the "split-line" exploit).
     */
    public static Map<String, Integer> aggregateQuantities(List<OrderLine> lines) {
        Map<String, Integer> quantityByProductId = new LinkedHashMap<>();
        try {
            for (OrderLine line : lines) {
                quantityByProductId.merge(line.productId(), line.quantity(), Math::addExact);
            }
        } catch (ArithmeticException e) {
            // Integer::sum would wrap to a negative number; no business cap on purpose, only the int range.
            throw new ValidationException("주문 수량이 너무 커요.");
        }
        return quantityByProductId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
