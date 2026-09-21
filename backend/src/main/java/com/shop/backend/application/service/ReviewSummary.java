package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Review;

import java.util.List;

/** A product's reviews plus the aggregate rating, as returned by {@link ReviewService#listReviews}. */
public record ReviewSummary(Double averageRating, int reviewCount, List<Review> reviews) {
}
