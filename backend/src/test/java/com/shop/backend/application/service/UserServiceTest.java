package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.domain.error.WithdrawalNotAllowedException;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final UserService userService = new UserService(userRepository, reviewRepository, passwordEncoder, CLOCK);

    private User user(Role role) {
        User user = new User("member01", "member01@example.com", "stored-hash", role, LocalDate.of(2000, 5, 5));
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void 성공_탈퇴하면_계정을_익명화하고_리뷰_작성자_이름을_바꾼다() {
        User user = user(Role.USER);
        when(passwordEncoder.matches("pw-1234", "stored-hash")).thenReturn(true);

        userService.withdraw("user-1", "pw-1234");

        assertThat(user.getUsername()).isEqualTo("deleted-user-1");
        assertThat(user.getEmail()).isEqualTo("deleted-user-1@deleted.invalid");
        assertThat(user.getPasswordHash()).isEqualTo("!");
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getDeletedAt()).isEqualTo(NOW);
        InOrder inOrder = inOrder(userRepository, reviewRepository);
        inOrder.verify(userRepository).saveAndFlush(user);
        inOrder.verify(reviewRepository).renameReviewer("user-1", UserService.WITHDRAWN_REVIEWER_NAME);
        assertThat(UserService.WITHDRAWN_REVIEWER_NAME).isEqualTo("탈퇴한 회원");
    }

    @Test
    void 실패_비밀번호가_틀리면_자격_증명_예외이고_아무것도_바꾸지_않는다() {
        User user = user(Role.USER);
        when(passwordEncoder.matches("wrong-pw", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.withdraw("user-1", "wrong-pw"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("아이디 또는 비밀번호가 올바르지 않습니다.");

        assertThat(user.isDeleted()).isFalse();
        assertThat(user.getUsername()).isEqualTo("member01");
        verify(userRepository, never()).saveAndFlush(user);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void 실패_없는_사용자면_자격_증명_예외() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw("ghost", "pw-1234"))
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    void 실패_이미_탈퇴한_계정이면_멱등하게_넘기지_않고_자격_증명_예외() {
        User user = user(Role.USER);
        user.withdraw(NOW);
        when(passwordEncoder.matches("pw-1234", "!")).thenReturn(true);

        assertThatThrownBy(() -> userService.withdraw("user-1", "pw-1234"))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(userRepository, never()).saveAndFlush(user);
        verifyNoInteractions(reviewRepository);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "SELLER"})
    void 실패_관리자와_판매자_계정은_비밀번호와_상관없이_403이고_비밀번호를_검증하지_않는다(Role role) {
        User user = user(role);

        assertThatThrownBy(() -> userService.withdraw("user-1", "pw-1234"))
                .isInstanceOf(WithdrawalNotAllowedException.class)
                .hasMessage("관리자·판매자 계정은 탈퇴할 수 없어요.");

        assertThat(user.isDeleted()).isFalse();
        assertThat(user.getUsername()).isEqualTo("member01");
        verifyNoInteractions(passwordEncoder, reviewRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 실패_비밀번호가_비었으면_조회_없이_검증_예외(String password) {
        assertThatThrownBy(() -> userService.withdraw("user-1", password))
                .isInstanceOf(ValidationException.class)
                .hasMessage("비밀번호를 입력해주세요.");

        verifyNoInteractions(userRepository, reviewRepository, passwordEncoder);
    }
}
