package com.shop.backend.domain;

import java.util.Set;

/** Business-rule validation for new product registration. */
public final class ProductValidation {

    /** Must match the category list shown in the storefront sidebar and admin form. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "뷰티", "신발", "상의", "아우터", "바지", "원피스/스커트",
            "가방", "모자", "소품", "속옷/홈웨어", "스포츠/레저"
    );

    private ProductValidation() {
    }

    public static void validateNewProduct(String name, String description, Integer price, String imageUrl, Integer stock, String category) {
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
        if (category != null && !category.isEmpty() && !ALLOWED_CATEGORIES.contains(category)) {
            throw new ValidationException("올바르지 않은 카테고리입니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
