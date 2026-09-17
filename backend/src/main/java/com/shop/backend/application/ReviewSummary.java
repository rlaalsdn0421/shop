package com.shop.backend.application;

import com.shop.backend.domain.Review;

import java.util.List;

/** A product's reviews plus the aggregate rating, as returned by {@link ReviewService#listReviews}. */
public record ReviewSummary(Double averageRating, int reviewCount, List<Review> reviews) {
}
