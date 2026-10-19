package com.shop.backend.domain.entity;

/** One option of a product edit: new numbers plus the stock the admin saw ({@code expectedStock}, checked under the row lock). */
public record OptionEdit(String id, Integer price, Integer originalPrice, Integer stock, Integer expectedStock) {
}
