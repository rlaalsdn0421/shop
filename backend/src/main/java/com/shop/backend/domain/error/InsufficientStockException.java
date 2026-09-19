package com.shop.backend.domain.error;

public class InsufficientStockException extends DomainException {
    public InsufficientStockException(String productName) {
        super("재고가 부족합니다: " + productName);
    }
}
