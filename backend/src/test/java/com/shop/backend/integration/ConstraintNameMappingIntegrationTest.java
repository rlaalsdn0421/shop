package com.shop.backend.integration;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.ReviewService;
import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateReviewException;
import com.shop.backend.domain.error.DuplicateUsernameException;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The services map a unique violation to a domain exception by looking for the constraint NAME in the driver's
 * message. The services check "exists" first, so the constraint path is only reachable in a race; here the exists
 * check is stubbed to "false" (everything else delegates to the real repository) so the REAL Postgres/Hibernate
 * exception reaches the mapping code deterministically.
 */
class ConstraintNameMappingIntegrationTest extends PostgresIntegrationTest {

    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 1);
    private static final String PASSWORD = "pass1234!";

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    /** AuthService whose exists-checks always say "free", like a concurrent request that has not committed yet. */
    private AuthService authServiceBlindToExistingUsers() {
        UserRepository blind = mock(UserRepository.class, delegatesTo(userRepository));
        doReturn(false).when(blind).existsByUsername("taken_name");
        doReturn(false).when(blind).existsByUsername("fresh_name");
        doReturn(false).when(blind).existsByEmail("taken@example.com");
        doReturn(false).when(blind).existsByEmail("fresh@example.com");
        return new AuthService(blind, passwordEncoder, jwtService, Clock.systemUTC());
    }

    @Test
    void 성공_회원가입_아이디_중복은_사전_검사로_DuplicateUsernameException이_된다() {
        authService.register("taken_name", "taken@example.com", PASSWORD, BIRTH);

        assertThatThrownBy(() -> authService.register("taken_name", "fresh@example.com", PASSWORD, BIRTH))
                .isInstanceOf(DuplicateUsernameException.class);
        assertThatThrownBy(() -> authService.register("fresh_name", "taken@example.com", PASSWORD, BIRTH))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void 성공_경쟁으로_DB까지_온_아이디_중복은_제약_이름으로_DuplicateUsernameException이_된다() {
        authService.register("taken_name", "taken@example.com", PASSWORD, BIRTH);

        assertThatThrownBy(() -> authServiceBlindToExistingUsers()
                .register("taken_name", "fresh@example.com", PASSWORD, BIRTH))
                .isInstanceOf(DuplicateUsernameException.class);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 성공_경쟁으로_DB까지_온_이메일_중복은_제약_이름으로_DuplicateEmailException이_된다() {
        authService.register("taken_name", "taken@example.com", PASSWORD, BIRTH);

        assertThatThrownBy(() -> authServiceBlindToExistingUsers()
                .register("fresh_name", "taken@example.com", PASSWORD, BIRTH))
                .isInstanceOf(DuplicateEmailException.class);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 성공_실제_드라이버_예외_메시지에_서비스가_찾는_제약_이름이_들어_있다() {
        data.user("taken_name", "taken@example.com");
        data.product("p1", null, 1000, null, TestData.T0);
        String userId = data.user();
        data.review("p1", userId, 5);

        assertThat(rootMessage(() -> data.user("taken_name", "other@example.com"))).contains("uq_users_username");
        assertThat(rootMessage(() -> data.user("other_name", "taken@example.com"))).contains("uq_users_email");
        assertThat(rootMessage(() -> data.review("p1", userId, 4))).contains("uq_reviews_product_user");
    }

    @Test
    void 성공_리뷰_중복은_사전_검사로_DuplicateReviewException이_된다() {
        data.product("p1", null, 1000, null, TestData.T0);
        String userId = data.user();
        reviewService.createReview("p1", userId, "작성자", 5, "좋아요");

        assertThatThrownBy(() -> reviewService.createReview("p1", userId, "작성자", 4, "또 씀"))
                .isInstanceOf(DuplicateReviewException.class);
    }

    @Test
    void 성공_경쟁으로_DB까지_온_리뷰_중복은_제약_이름으로_DuplicateReviewException이_된다() {
        data.product("p1", null, 1000, null, TestData.T0);
        String userId = data.user();
        reviewService.createReview("p1", userId, "작성자", 5, "좋아요");

        ReviewRepository blind = mock(ReviewRepository.class, delegatesTo(reviewRepository));
        doReturn(false).when(blind).existsByProductIdAndUserId("p1", userId);
        ReviewService blindService = new ReviewService(productRepository, blind);

        assertThatThrownBy(() -> blindService.createReview("p1", userId, "작성자", 4, "또 씀"))
                .isInstanceOf(DuplicateReviewException.class);
        assertThat(reviewRepository.count()).isEqualTo(1);
    }

    private static String rootMessage(Runnable action) {
        try {
            action.run();
        } catch (DataIntegrityViolationException ex) {
            return String.valueOf(ex.getMostSpecificCause().getMessage());
        } catch (RuntimeException ex) {
            Throwable root = ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return String.valueOf(root.getMessage());
        }
        throw new AssertionError("expected a constraint violation");
    }
}
