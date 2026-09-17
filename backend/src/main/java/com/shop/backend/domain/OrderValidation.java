package com.shop.backend.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business-rule validation for order placement, mirroring the storefront's original rules. */
public final class OrderValidation {

    private OrderValidation() {
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
        for (OrderLine line : lines) {
            quantityByProductId.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return quantityByProductId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
