package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ReviewService;
import com.shop.backend.presentation.dto.ProductDtos;
import com.shop.backend.presentation.dto.ReviewDtos;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{productId}/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ReviewDtos.ReviewListResponse listReviews(@PathVariable String productId) {
        return ReviewDtos.ReviewListResponse.from(reviewService.listReviews(productId));
    }

    @PostMapping
    public ProductDtos.IdResponse createReview(@PathVariable String productId, @RequestBody ReviewDtos.NewReviewRequest request,
                                               Authentication authentication) {
        // JwtAuthFilter sets the user id as the principal (its name); SecurityConfig guarantees it is present
        var review = reviewService.createReview(
                productId, authentication.getName(), request.reviewerName(), request.rating(), request.comment());
        return new ProductDtos.IdResponse(review.getId());
    }
}
