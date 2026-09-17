package com.shop.backend.presentation;

import com.shop.backend.application.ReviewSummary;
import com.shop.backend.domain.Review;

import java.time.Instant;
import java.util.List;

/** Response/request shapes for the product review endpoints. */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record NewReviewRequest(String reviewerName, Integer rating, String comment) {
    }

    public record ReviewView(String id, String reviewerName, Integer rating, String comment, Instant createdAt) {
        static ReviewView from(Review r) {
            return new ReviewView(r.getId(), r.getReviewerName(), r.getRating(), r.getComment(), r.getCreatedAt());
        }
    }

    public record ReviewListResponse(Double averageRating, int reviewCount, List<ReviewView> reviews) {
        static ReviewListResponse from(ReviewSummary summary) {
            List<ReviewView> views = summary.reviews().stream().map(ReviewView::from).toList();
            return new ReviewListResponse(summary.averageRating(), summary.reviewCount(), views);
        }
    }
}
