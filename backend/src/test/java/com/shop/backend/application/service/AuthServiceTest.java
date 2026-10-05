package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateUsernameException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String BAD_CREDENTIALS = "아이디 또는 비밀번호가 올바르지 않습니다.";
    private static final String DUMMY_HASH = "hash:not-a-real-password";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthService authService;

    AuthServiceTest() {
        // must be stubbed before construction: the constructor encodes a dummy hash once
        when(passwordEncoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    private static DataIntegrityViolationException integrityViolation(String causeMessage) {
        return new DataIntegrityViolationException("x", new RuntimeException(causeMessage));
    }

    // ---- register ----

    @Test
    void 성공_가입하면_아이디_이메일_해시된_비밀번호_USER_역할로_저장하고_저장된_엔티티를_돌려준다() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = authService.register("user_01", "user01@example.com", "password-1");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertThat(user.getUsername()).isEqualTo("user_01");
        assertThat(user.getEmail()).isEqualTo("user01@example.com");
        assertThat(user.getPasswordHash()).isEqualTo("hash:password-1");
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(result).isSameAs(user);
    }

    @Test
    void 실패_이미_있는_아이디면_이메일_검사와_저장_없이_중복_아이디_예외() {
        when(userRepository.existsByUsername("user_01")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("user_01", "user01@example.com", "password-1"))
                .isInstanceOf(DuplicateUsernameException.class)
                .hasMessage("이미 사용 중인 아이디입니다.");

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_이미_있는_이메일이면_저장_없이_중복_이메일_예외() {
        when(userRepository.existsByEmail("user01@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("user_01", "user01@example.com", "password-1"))
                .isInstanceOf(DuplicateEmailException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");

        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void 실패_아이디_형식이_잘못되면_저장소를_전혀_호출하지_않고_검증_예외() {
        assertThatThrownBy(() -> authService.register("Bob", "bob@example.com", "password-1"))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(userRepository);
    }

    @Test
    void 실패_동시_가입으로_아이디_유니크_제약에_걸리면_중복_아이디_예외로_바꾼다() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(integrityViolation("duplicate key value violates unique constraint \"uq_users_username\""));

        assertThatThrownBy(() -> authService.register("user_01", "user01@example.com", "password-1"))
                .isInstanceOf(DuplicateUsernameException.class);
    }

    @Test
    void 실패_동시_가입으로_이메일_유니크_제약에_걸리면_중복_이메일_예외로_바꾼다() {
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(integrityViolation("duplicate key value violates unique constraint \"uq_users_email\""));

        assertThatThrownBy(() -> authService.register("user_01", "user01@example.com", "password-1"))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void 실패_관련_없는_무결성_오류는_원래_예외를_그대로_던진다() {
        DataIntegrityViolationException original = integrityViolation("null value in column \"role\"");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(original);

        assertThatThrownBy(() -> authService.register("user_01", "user01@example.com", "password-1"))
                .isSameAs(original);
    }

    // ---- login ----

    @Test
    void 성공_로그인하면_토큰과_역할_아이디를_돌려준다() {
        User user = new User("boss01", null, "stored-hash", Role.ADMIN);
        when(userRepository.findByUsername("boss01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pw-1234", "stored-hash")).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("jwt-token");

        AuthService.LoginResult result = authService.login("boss01", "pw-1234");

        assertThat(result).isEqualTo(new AuthService.LoginResult("jwt-token", "ADMIN", "boss01"));
        verify(userRepository).findByUsername("boss01");
    }

    @Test
    void 실패_없는_아이디면_더미_해시와_비교한_뒤_토큰_없이_자격_증명_예외() {
        when(userRepository.findByUsername("ghost01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost01", "pw-1234"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(BAD_CREDENTIALS);

        verify(passwordEncoder).matches("pw-1234", DUMMY_HASH);
        verifyNoInteractions(jwtService);
    }

    @Test
    void 실패_비밀번호가_틀리면_토큰_없이_자격_증명_예외() {
        User user = new User("boss01", null, "stored-hash", Role.ADMIN);
        when(userRepository.findByUsername("boss01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pw", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("boss01", "wrong-pw"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(BAD_CREDENTIALS);

        verifyNoInteractions(jwtService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 실패_아이디가_비었으면_조회_없이_자격_증명_예외(String username) {
        assertThatThrownBy(() -> authService.login(username, "pw-1234"))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(BAD_CREDENTIALS);

        verifyNoInteractions(userRepository, jwtService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 실패_비밀번호가_비었으면_조회_없이_자격_증명_예외(String password) {
        assertThatThrownBy(() -> authService.login("boss01", password))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage(BAD_CREDENTIALS);

        verifyNoInteractions(userRepository, jwtService);
    }
}
