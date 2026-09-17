package com.shop.backend.domain;

public class InsufficientStockException extends DomainException {
    public InsufficientStockException(String productName) {
        super("재고가 부족합니다: " + productName);
    }
}
