package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.error.DuplicateReviewException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductRepository;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewService service = new ReviewService(productRepository, reviewRepository);
    private final Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);

    private static DataIntegrityViolationException violation(String cause) {
        return new DataIntegrityViolationException("x", new RuntimeException(cause));
    }

    @Test
    void 성공_리뷰를_작성한_사용자_id와_함께_저장한다() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product));
        when(reviewRepository.existsByProductIdAndUserId("p1", "u1")).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review saved = service.createReview("p1", "u1", "홍길동", 5, "좋아요");

        ArgumentCaptor<Review> captor = ArgumentCaptor.forClass(Review.class);
        verify(reviewRepository).saveAndFlush(captor.capture());
        assertThat(saved).isSameAs(captor.getValue());
        assertThat(saved.getUserId()).isEqualTo("u1");
        assertThat(saved.getReviewerName()).isEqualTo("홍길동");
    }

    @Test
    void 실패_이미_리뷰를_남겼으면_저장하지_않고_중복으로_거부한다() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product));
        when(reviewRepository.existsByProductIdAndUserId("p1", "u1")).thenReturn(true);

        assertThatThrownBy(() -> service.createReview("p1", "u1", "홍길동", 5, "좋아요"))
                .isInstanceOf(DuplicateReviewException.class)
                .hasMessage("이미 이 상품에 리뷰를 남기셨어요.");

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void 실패_동시에_작성해_유니크_인덱스에_걸리면_중복으로_거부한다() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product));
        when(reviewRepository.existsByProductIdAndUserId("p1", "u1")).thenReturn(false); // race: both passed the check
        when(reviewRepository.saveAndFlush(any())).thenThrow(violation(
                "ERROR: duplicate key value violates unique constraint \"uq_reviews_product_user\""));

        assertThatThrownBy(() -> service.createReview("p1", "u1", "홍길동", 5, "좋아요"))
                .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void 실패_다른_무결성_오류는_그대로_던진다() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product));
        DataIntegrityViolationException other = violation("violates foreign key constraint \"reviews_user_id_fkey\"");
        when(reviewRepository.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> service.createReview("p1", "u1", "홍길동", 5, "좋아요")).isSameAs(other);
    }

    @Test
    void 실패_상품이_없으면_상품없음을_던지고_중복검사도_하지_않는다() {
        when(productRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createReview("missing", "u1", "홍길동", 5, "좋아요"))
                .isInstanceOf(ProductNotFoundException.class);

        verify(reviewRepository, never()).existsByProductIdAndUserId(any(), any());
    }

    @Test
    void 실패_평점이_범위를_벗어나면_검증_오류이고_저장하지_않는다() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.createReview("p1", "u1", "홍길동", 9, "좋아요"))
                .isInstanceOf(ValidationException.class);

        verify(reviewRepository, never()).saveAndFlush(any());
    }
}
