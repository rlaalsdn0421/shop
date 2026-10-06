package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.TooManyRequestsException;
import com.shop.backend.presentation.web.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateUsernameException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("9.9.9.9");
    }

    @Test
    void 성공_회원가입한다() throws Exception {
        User user = new User("user01", "a@a.com", "hashed", Role.USER);
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(authService.register(any(), any(), any())).thenReturn(user);

        String body = """
                {"username":"user01","email":"a@a.com","password":"password1"}
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-1"));
    }

    @Test
    void 실패_이미_사용_중인_아이디면_400을_반환한다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new DuplicateUsernameException());

        String body = """
                {"username":"user01","email":"a@a.com","password":"password1"}
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 아이디입니다."));
    }

    @Test
    void 실패_이미_등록된_이메일이면_400을_반환한다() throws Exception {
        when(authService.register(any(), any(), any())).thenThrow(new DuplicateEmailException());

        String body = """
                {"username":"user01","email":"a@a.com","password":"password1"}
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 이메일입니다."));
    }

    @Test
    void 성공_아이디로_로그인한다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new AuthService.LoginResult("token123", "ADMIN", "seller01"));

        String body = """
                {"username":"seller01","password":"password1"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("token123"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.username").value("seller01"));
    }

    @Test
    void 실패_잘못된_자격증명이면_401을_반환한다() throws Exception {
        when(authService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        String body = """
                {"username":"seller01","password":"wrong"}
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다."));
    }

    @Test
    void 실패_로그인에_실패하면_해석된_IP로_실패를_기록한다() throws Exception {
        when(authService.login(any(), any())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"seller01\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        verify(rateLimiter).assertLoginAllowed("9.9.9.9");
        verify(rateLimiter).recordLoginFailure("9.9.9.9");
    }

    @Test
    void 성공_로그인에_성공하면_실패를_기록하지_않는다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new AuthService.LoginResult("token123", "ADMIN", "seller01"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"seller01\",\"password\":\"password1\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter).assertLoginAllowed("9.9.9.9");
        verify(rateLimiter, never()).recordLoginFailure(any());
    }

    @Test
    void 실패_로그인이_제한되면_429와_Retry_After를_반환하고_로그인을_호출하지_않는다() throws Exception {
        doThrow(new TooManyRequestsException(170)).when(rateLimiter).assertLoginAllowed("9.9.9.9");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"seller01\",\"password\":\"password1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "170"))
                .andExpect(jsonPath("$.error").value("시도 횟수를 초과했습니다. 3분 후에 다시 시도해 주세요."));

        verifyNoInteractions(authService);
        verify(rateLimiter, never()).recordLoginFailure(any());
    }

    @Test
    void 성공_회원가입은_해석된_IP로_시도를_기록한다() throws Exception {
        User user = new User("user01", "a@a.com", "hashed", Role.USER);
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(authService.register(any(), any(), any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\"}"))
                .andExpect(status().isOk());

        verify(rateLimiter).checkAndRecordRegister("9.9.9.9");
    }

    @Test
    void 실패_회원가입이_제한되면_429를_반환하고_가입을_호출하지_않는다() throws Exception {
        doThrow(new TooManyRequestsException(300)).when(rateLimiter).checkAndRecordRegister("9.9.9.9");

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.error").value("시도 횟수를 초과했습니다. 5분 후에 다시 시도해 주세요."));

        verifyNoInteractions(authService);
    }
}
