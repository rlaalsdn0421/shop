package com.shop.backend.domain.error;

public class DuplicateReviewException extends DomainException {
    public DuplicateReviewException() {
        super("이미 이 상품에 리뷰를 남기셨어요.");
    }
}
