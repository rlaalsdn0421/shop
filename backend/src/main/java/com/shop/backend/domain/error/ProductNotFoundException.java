package com.shop.backend.domain.error;

public class ProductNotFoundException extends DomainException {
    public ProductNotFoundException(String productId) {
        super("상품을 찾을 수 없습니다: " + productId);
    }
}
