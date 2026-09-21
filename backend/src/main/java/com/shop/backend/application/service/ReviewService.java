package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.entity.ReviewValidation;
import com.shop.backend.infrastructure.repository.ProductRepository;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ReviewService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;

    public ReviewService(ProductRepository productRepository, ReviewRepository reviewRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional
    public Review createReview(String productId, String reviewerName, Integer rating, String comment) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        ReviewValidation.validateNewReview(reviewerName, rating, comment);
        return reviewRepository.save(new Review(product, reviewerName, rating, comment));
    }

    @Transactional(readOnly = true)
    public ReviewSummary listReviews(String productId) {
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        List<Review> reviews = reviewRepository.findByProductIdOrderByCreatedAtDesc(productId);
        Double averageRating = reviewRepository.averageRatingByProductId(productId);
        return new ReviewSummary(averageRating, reviews.size(), reviews);
    }
}
