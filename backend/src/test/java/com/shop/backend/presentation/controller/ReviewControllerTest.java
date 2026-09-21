package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ReviewService;
import com.shop.backend.application.service.ReviewSummary;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@Import({SecurityConfig.class, JwtService.class})
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewService reviewService;

    @Test
    void 성공_리뷰목록을_조회한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);
        Review review = new Review(product, "홍길동", 5, "좋아요");
        ReviewSummary summary = new ReviewSummary(5.0, 1, List.of(review));
        when(reviewService.listReviews("p1")).thenReturn(summary);

        mockMvc.perform(get("/api/products/p1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(5.0))
                .andExpect(jsonPath("$.reviewCount").value(1));
    }

    @Test
    void 실패_존재하지_않는_상품이면_404를_반환한다() throws Exception {
        when(reviewService.listReviews("missing")).thenThrow(new ProductNotFoundException("missing"));

        mockMvc.perform(get("/api/products/missing/reviews"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 성공_리뷰를_생성한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);
        Review review = new Review(product, "홍길동", 5, "좋아요");
        ReflectionTestUtils.setField(review, "id", "review-1");
        when(reviewService.createReview(eq("p1"), any(), any(), any())).thenReturn(review);

        String body = """
                {"reviewerName":"홍길동","rating":5,"comment":"좋아요"}
                """;

        mockMvc.perform(post("/api/products/p1/reviews")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("review-1"));
    }

    @Test
    void 실패_평점이_올바르지_않으면_400을_반환한다() throws Exception {
        when(reviewService.createReview(eq("p1"), any(), any(), any()))
                .thenThrow(new ValidationException("평점은 1~5 사이의 정수여야 합니다."));

        String body = """
                {"reviewerName":"홍길동","rating":9,"comment":"좋아요"}
                """;

        mockMvc.perform(post("/api/products/p1/reviews")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("평점은 1~5 사이의 정수여야 합니다."));
    }
}
