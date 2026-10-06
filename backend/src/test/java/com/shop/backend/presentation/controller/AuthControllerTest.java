package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateUsernameException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.TooManyRequestsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import com.shop.backend.presentation.web.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class})
class AuthControllerTest {

    private static final String REGISTER_BODY = """
            {"username":"user01","email":"a@a.com","password":"password1"}
            """;
    private static final String LOGIN_BODY = """
            {"username":"seller01","password":"password1"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private ClientIpResolver clientIpResolver;

    private final RateLimiter.Attempt attempt = org.mockito.Mockito.mock(RateLimiter.Attempt.class);

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("9.9.9.9");
        when(rateLimiter.acquireLogin("9.9.9.9")).thenReturn(attempt);
        when(rateLimiter.acquireRegister("9.9.9.9")).thenReturn(attempt);
    }

    @Test
    void 성공_회원가입한다() throws Exception {
        User user = new User("user01", "a@a.com", "hashed", Role.USER);
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(authService.register(any(), any(), any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-1"));

        verify(rateLimiter).acquireRegister("9.9.9.9");
        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_이미_사용_중인_아이디면_400을_반환하고_시도는_세어진다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new DuplicateUsernameException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 아이디입니다."));

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_이미_등록된_이메일이면_400을_반환하고_시도는_세어진다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 이메일입니다."));

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_검증에_실패한_가입도_시도로_세어진다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new ValidationException("비밀번호 규칙 위반"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest());

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_가입_중_예상치_못한_오류가_나도_시도가_세어지고_해제된다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isInternalServerError());

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_가입이_제한되면_429와_Retry_After를_반환하고_가입을_호출하지_않는다() throws Exception {
        when(rateLimiter.acquireRegister("9.9.9.9")).thenThrow(new TooManyRequestsException(300));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.error").value("시도 횟수를 초과했습니다. 5분 후에 다시 시도해 주세요."));

        verifyNoInteractions(authService);
        verifyNoInteractions(attempt);
    }

    @Test
    void 성공_아이디로_로그인한다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new AuthService.LoginResult("token123", "ADMIN", "seller01"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.username").value("seller01"));

        verify(attempt).release();
        verify(attempt, never()).countAndRelease();
    }

    @Test
    void 실패_잘못된_자격증명이면_401을_반환하고_실패를_기록한다() throws Exception {
        when(authService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"seller01\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다."));

        verify(rateLimiter).acquireLogin("9.9.9.9");
        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_빈_아이디로_로그인해도_실패로_센다() throws Exception {
        // AuthService throws InvalidCredentialsException for blank input (see AuthServiceTest)
        when(authService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized());

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_로그인_중_예상치_못한_오류면_500이고_실패는_기록하지_않지만_해제한다() throws Exception {
        when(authService.login(any(), any())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(LOGIN_BODY))
                .andExpect(status().isInternalServerError());

        verify(attempt, never()).countAndRelease();
        verify(attempt).release();
    }

    @Test
    void 실패_로그인이_제한되면_429와_Retry_After를_반환하고_로그인을_호출하지_않는다() throws Exception {
        when(rateLimiter.acquireLogin("9.9.9.9")).thenThrow(new TooManyRequestsException(170));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "170"))
                .andExpect(jsonPath("$.error").value("시도 횟수를 초과했습니다. 3분 후에 다시 시도해 주세요."));

        verifyNoInteractions(authService);
        verifyNoInteractions(attempt);
    }

    @Test
    void 실패_같은_IP의_요청이_처리_중이면_busy_메시지로_429를_반환하고_로그인을_호출하지_않는다() throws Exception {
        when(rateLimiter.acquireLogin("9.9.9.9")).thenThrow(TooManyRequestsException.busy());

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요."));

        verifyNoInteractions(authService);
        verifyNoInteractions(attempt);
    }
}
