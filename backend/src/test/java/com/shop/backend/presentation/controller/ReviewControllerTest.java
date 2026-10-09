package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ReviewService;
import com.shop.backend.application.service.ReviewSummary;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.DuplicateReviewException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@Import({SecurityConfig.class, JwtService.class})
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

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
    @WithMockUser
    void 성공_리뷰를_생성한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);
        Review review = new Review(product, "홍길동", 5, "좋아요");
        ReflectionTestUtils.setField(review, "id", "review-1");
        when(reviewService.createReview(eq("p1"), any(), any(), any(), any())).thenReturn(review);

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
    @WithMockUser
    void 실패_평점이_올바르지_않으면_400을_반환한다() throws Exception {
        when(reviewService.createReview(eq("p1"), any(), any(), any(), any()))
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

    private static final String BODY = """
            {"reviewerName":"홍길동","rating":5,"comment":"좋아요"}
            """;

    @Test
    void 실패_로그인_없이_리뷰를_쓰면_401이고_서비스는_호출되지_않는다() throws Exception {
        mockMvc.perform(post("/api/products/p1/reviews").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("인증이 필요합니다."));

        verifyNoInteractions(reviewService);
    }

    @Test
    void 실패_잘못된_토큰으로_리뷰를_쓰면_401이다() throws Exception {
        mockMvc.perform(post("/api/products/p1/reviews").header("Authorization", "Bearer not-a-jwt")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    void 성공_리뷰조회는_로그인_없이_가능하다() throws Exception {
        when(reviewService.listReviews("p1")).thenReturn(new ReviewSummary(null, 0, List.of()));

        mockMvc.perform(get("/api/products/p1/reviews")).andExpect(status().isOk());
    }

    @Test
    void 성공_토큰의_사용자_id가_서비스에_넘어가고_응답에는_나오지_않는다() throws Exception {
        User user = new User("someone", "a@example.test", "hash", Role.USER, java.time.LocalDate.of(2000, 1, 1));
        ReflectionTestUtils.setField(user, "id", "user-123");
        Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);
        Review review = new Review(product, "user-123", "홍길동", 5, "좋아요");
        ReflectionTestUtils.setField(review, "id", "review-1");
        when(reviewService.createReview("p1", "user-123", "홍길동", 5, "좋아요")).thenReturn(review);

        mockMvc.perform(post("/api/products/p1/reviews")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("review-1"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("user-123"))));

        verify(reviewService).createReview("p1", "user-123", "홍길동", 5, "좋아요");
    }

    @Test
    @WithMockUser
    void 실패_이미_리뷰를_남긴_상품이면_409를_반환한다() throws Exception {
        when(reviewService.createReview(eq("p1"), any(), any(), any(), any())).thenThrow(new DuplicateReviewException());

        mockMvc.perform(post("/api/products/p1/reviews").contentType("application/json").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("이미 이 상품에 리뷰를 남기셨어요."));
    }

    @Test
    @WithMockUser
    void 실패_리뷰_작성_시_상품이_없으면_404를_반환한다() throws Exception {
        when(reviewService.createReview(eq("missing"), any(), any(), any(), any()))
                .thenThrow(new ProductNotFoundException("missing"));

        mockMvc.perform(post("/api/products/missing/reviews").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound());
    }
}
