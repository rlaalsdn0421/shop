package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

/** Business-rule validation for new product reviews. */
public final class ReviewValidation {

    private ReviewValidation() {
    }

    public static void validateNewReview(String reviewerName, Integer rating, String comment) {
        if (isBlank(reviewerName) || reviewerName.length() > 100) {
            throw new ValidationException("작성자 이름을 올바르게 입력해주세요.");
        }
        if (isBlank(comment) || comment.length() > 1000) {
            throw new ValidationException("리뷰 내용을 올바르게 입력해주세요.");
        }
        if (rating == null || rating < 1 || rating > 5) {
            throw new ValidationException("평점은 1~5 사이의 정수여야 합니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
