package com.shop.backend.integration;

import com.shop.backend.application.service.ReviewService;
import com.shop.backend.application.service.UserService;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.infrastructure.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review creation racing account withdrawal on a real Postgres: whatever the order, a withdrawn user's review must
 * never keep the original reviewer name (personal data). Same context setup as UserWithdrawalIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserWithdrawalRaceIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    private static final String PASSWORD = "pass1234!";
    private static final String ORIGINAL_NAME = "원래이름";
    private static final String MASKED_NAME = "탈퇴한 회원";
    private static final int ROUNDS = 200;
    // Cost 4: the hash carries its own cost, so withdraw's password check is as fast as the review path.
    private static final String FAST_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private UserService userService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private JwtService jwtService;

    private TestData data;
    private TransactionTemplate tx;

    @BeforeEach
    void cleanTables() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        data = new TestData(em, txManager);
        tx = new TransactionTemplate(txManager);
        data.product("p1", null, 1000, null, TestData.T0);
    }

    private User member(String username) {
        User user = new User(username, username + "@example.com", FAST_HASH, Role.USER, LocalDate.of(1990, 1, 1));
        tx.executeWithoutResult(s -> em.persist(user));
        return user;
    }

    private List<String> reviewerNames(String userId) {
        return jdbc.queryForList("SELECT reviewer_name FROM reviews WHERE user_id = ?", String.class, userId);
    }

    // ---- the race ----

    @Test
    void 성공_리뷰_작성과_탈퇴가_동시에_일어나도_탈퇴한_사용자의_리뷰에_원래_이름이_남지_않는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                User user = member("racer_" + round);
                CyclicBarrier startTogether = new CyclicBarrier(2);
                // sweep the relative start offset (-1.6 ms .. +1.6 ms) so the two transactions interleave differently
                long offsetNanos = ((round % 9) - 4) * 400_000L;

                Future<Boolean> review = pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    LockSupport.parkNanos(Math.max(0, offsetNanos));
                    try {
                        reviewService.createReview("p1", user.getId(), ORIGINAL_NAME, 5, "좋아요");
                        return true;
                    } catch (InvalidCredentialsException rejectedAfterWithdrawal) {
                        return false; // the only allowed failure
                    }
                });
                Future<Void> withdraw = pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    LockSupport.parkNanos(Math.max(0, -offsetNanos));
                    userService.withdraw(user.getId(), PASSWORD);
                    return null;
                });

                boolean created = review.get(30, TimeUnit.SECONDS);
                withdraw.get(30, TimeUnit.SECONDS);

                assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class,
                        user.getId())).as("round %d: withdrawn", round).isTrue();
                if (created) {
                    assertThat(reviewerNames(user.getId())).as("round %d: review kept but masked", round)
                            .containsExactly(MASKED_NAME);
                } else {
                    assertThat(reviewerNames(user.getId())).as("round %d: review rejected", round).isEmpty();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT 1 FROM reviews WHERE reviewer_name = ?", ORIGINAL_NAME)).isEmpty();
    }

    // ---- deterministic ordering, with the withdrawal held open ----

    /** Runs {@code action} in a thread and, in this one, an open transaction that marks the user withdrawn. */
    private <T> T whileWithdrawalIsUncommitted(String userId, Callable<T> concurrentAction, long holdMillis)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", userId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<T> action = pool.submit(concurrentAction);
            try {
                action.get(holdMillis, TimeUnit.MILLISECONDS);
                throw new AssertionError("the review did not wait for the uncommitted withdrawal");
            } catch (TimeoutException blocked) {
                // expected while the withdrawal holds the row
            } catch (ExecutionException early) {
                // lock_timeout may already have fired (see the timeout test): hand the failure to the caller
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                throw early;
            }
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return action.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 성공_탈퇴가_커밋되기_전의_리뷰는_기다렸다가_커밋_뒤에_거절되고_저장되지_않는다() throws Exception {
        User user = member("waiter_01");

        assertThatThrownBy(() -> whileWithdrawalIsUncommitted(user.getId(),
                () -> reviewService.createReview("p1", user.getId(), ORIGINAL_NAME, 5, "좋아요"), 800))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InvalidCredentialsException.class);

        assertThat(reviewerNames(user.getId())).isEmpty();
    }

    @Test
    void 실패_탈퇴가_3초_넘게_끝나지_않으면_리뷰는_락_대기_실패로_거절되고_저장되지_않는다() throws Exception {
        User user = member("waiter_02");

        assertThatThrownBy(() -> whileWithdrawalIsUncommitted(user.getId(),
                () -> reviewService.createReview("p1", user.getId(), ORIGINAL_NAME, 5, "좋아요"), 10_000))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class); // GlobalExceptionHandler -> 503

        assertThat(reviewerNames(user.getId())).isEmpty();
    }

    @Test
    void 성공_탈퇴하지_않은_사용자의_리뷰는_탈퇴_없이_원래_이름으로_저장된다() {
        User user = member("steady_01");

        reviewService.createReview("p1", user.getId(), ORIGINAL_NAME, 5, "좋아요");

        assertThat(reviewerNames(user.getId())).containsExactly(ORIGINAL_NAME);
    }

    @Test
    void 실패_탈퇴를_먼저_마친_사용자는_리뷰를_쓸_수_없다() {
        User user = member("gone_0001");
        userService.withdraw(user.getId(), PASSWORD);

        assertThatThrownBy(() -> reviewService.createReview("p1", user.getId(), ORIGINAL_NAME, 5, "좋아요"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(reviewerNames(user.getId())).isEmpty();
    }

    // ---- V12 schema ----

    @Test
    void 성공_V12가_reviews_user_id_부분_인덱스와_users_deleted_at_컬럼을_만든다() {
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'reviews' AND indexname = 'idx_reviews_user_id'",
                String.class)).contains("(user_id)").contains("WHERE (user_id IS NOT NULL)");
        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'deleted_at'",
                String.class)).isEqualTo("timestamp without time zone");
    }

    // ---- 401 code through the real security chain ----

    @Test
    void 실패_토큰_없이_탈퇴하면_401과_AUTH_REQUIRED_코드() throws Exception {
        mockMvc.perform(delete("/api/users/me").contentType("application/json").content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void 실패_탈퇴한_사용자의_유효한_토큰은_401과_AUTH_REQUIRED_코드() throws Exception {
        User user = member("expired_1");
        String token = jwtService.generateToken(user);
        userService.withdraw(user.getId(), PASSWORD);

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}
