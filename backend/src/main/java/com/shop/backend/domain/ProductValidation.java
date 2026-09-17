package com.shop.backend.domain;

/** Business-rule validation for new product registration. */
public final class ProductValidation {

    private ProductValidation() {
    }

    public static void validateNewProduct(String name, String description, Integer price, String imageUrl, Integer stock) {
        if (isBlank(name) || isBlank(description) || isBlank(imageUrl)
                || name.length() > 200 || description.length() > 2000 || imageUrl.length() > 2000) {
            throw new ValidationException("이름, 설명, 이미지 URL을 올바르게 입력해주세요.");
        }
        if (price == null || price < 0) {
            throw new ValidationException("가격이 올바르지 않습니다.");
        }
        if (stock == null || stock < 0) {
            throw new ValidationException("재고 수량이 올바르지 않습니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
