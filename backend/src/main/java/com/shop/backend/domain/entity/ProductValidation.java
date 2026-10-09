package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.util.Set;

/** Business-rule validation for new product registration. */
public final class ProductValidation {

    /** Must match the category list shown in the storefront sidebar and admin form. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "뷰티", "신발", "상의", "아우터", "바지", "원피스/스커트",
            "가방", "모자", "소품", "속옷/홈웨어", "스포츠/레저"
    );

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_BEST_SIZE = 20;

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

    public static void validatePage(int page, int size) {
        if (page < 0) {
            throw new ValidationException("페이지 번호가 올바르지 않아요.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("상품 수는 1~" + MAX_PAGE_SIZE + "개 사이여야 해요.");
        }
        // the row offset (page * size) must fit an int, otherwise the query layer overflows -> 500
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ValidationException("페이지 번호가 너무 커요.");
        }
    }

    public static void validateBestSize(int size) {
        if (size < 1 || size > MAX_BEST_SIZE) {
            throw new ValidationException("상품 수는 1~" + MAX_BEST_SIZE + "개 사이여야 해요.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
