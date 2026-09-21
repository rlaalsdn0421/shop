package com.shop.backend.domain.entity;

/** One requested line of an incoming order: a product id and the quantity wanted. */
public record OrderLine(String productId, Integer quantity) {
}
