package com.shop.backend.domain.entity;

/** One requested line of an incoming order: a product id, an optional option id (required for products with options) and the quantity wanted. */
public record OrderLine(String productId, Integer quantity, String optionId) {

    /** A line for a product without options. */
    public OrderLine(String productId, Integer quantity) {
        this(productId, quantity, null);
    }
}
