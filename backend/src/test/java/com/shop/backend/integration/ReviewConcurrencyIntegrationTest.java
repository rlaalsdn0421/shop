package com.shop.backend.integration;

import com.shop.backend.application.service.ReviewService;
import com.shop.backend.domain.error.DuplicateReviewException;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** The partial unique index is the only thing that stops two simultaneous reviews by one user. */
class ReviewConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int THREADS = 6;

    @Test
    void 성공_같은_사용자가_같은_상품에_동시에_리뷰를_써도_하나만_저장된다() throws Exception {
        for (int round = 0; round < 5; round++) { // several rounds: the race is timing dependent
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            data.product("p1", null, 1000, null, TestData.T0);
            String userId = data.user();

            CyclicBarrier startTogether = new CyclicBarrier(THREADS);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<Object>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    Callable<Object> post = () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        try {
                            return reviewService.createReview("p1", userId, "작성자", 5, "동시에 씀");
                        } catch (DuplicateReviewException ex) {
                            return ex;
                        }
                    };
                    futures.add(pool.submit(post));
                }

                int created = 0;
                int duplicates = 0;
                for (Future<Object> future : futures) {
                    Object result = future.get(30, TimeUnit.SECONDS); // any other exception fails the test here
                    if (result instanceof DuplicateReviewException) {
                        duplicates++;
                    } else {
                        created++;
                    }
                }

                assertThat(created).as("round %d: created", round).isEqualTo(1);
                assertThat(duplicates).as("round %d: duplicates", round).isEqualTo(THREADS - 1);
                assertThat(reviewRepository.count()).as("round %d: rows", round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * The exists() pre-check is stubbed to "false" for every thread, so every request goes straight to INSERT and the
     * partial unique index uq_reviews_product_user is the ONLY defence (the natural race above can be won by the pre-check).
     */
    @Test
    void 성공_사전_검사가_모두_통과해도_유니크_인덱스가_하나만_저장되게_막는다() throws Exception {
        ReviewRepository blind = mock(ReviewRepository.class, delegatesTo(reviewRepository));
        doReturn(false).when(blind).existsByProductIdAndUserId(anyString(), anyString());
        ReviewService indexOnly = new ReviewService(productRepository, blind);

        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            data.product("p1", null, 1000, null, TestData.T0);
            String userId = data.user();

            CyclicBarrier startTogether = new CyclicBarrier(THREADS);
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            try {
                List<Future<Object>> futures = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    futures.add(pool.submit(() -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        try {
                            return indexOnly.createReview("p1", userId, "작성자", 5, "사전 검사 없이 동시에");
                        } catch (DuplicateReviewException ex) {
                            return ex;
                        }
                    }));
                }

                int created = 0;
                int duplicates = 0;
                for (Future<Object> future : futures) {
                    if (future.get(30, TimeUnit.SECONDS) instanceof DuplicateReviewException) {
                        duplicates++;
                    } else {
                        created++;
                    }
                }

                assertThat(created).as("round %d: created", round).isEqualTo(1);
                assertThat(duplicates).as("round %d: duplicates", round).isEqualTo(THREADS - 1);
                assertThat(reviewRepository.count()).as("round %d: rows", round).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void 성공_서로_다른_사용자가_동시에_쓰면_모두_저장된다() throws Exception {
        data.product("p1", null, 1000, null, TestData.T0);
        List<String> users = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            users.add(data.user());
        }

        CyclicBarrier startTogether = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String userId : users) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return reviewService.createReview("p1", userId, "작성자", 4, "각자 씀");
                }));
            }
            for (Future<Object> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(reviewRepository.count()).isEqualTo(THREADS);
    }
}
