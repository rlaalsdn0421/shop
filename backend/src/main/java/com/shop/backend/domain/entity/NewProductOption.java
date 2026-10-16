package com.shop.backend.domain.entity;

/** One option row of a product being registered (before {@link ProductValidation#normalizeOptions} cleans it). */
public record NewProductOption(String colorName, String colorHex, String sizeLabel, Integer price,
                               Integer originalPrice, Integer stock) {
}
