package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.application.service.UserService;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.TooManyRequestsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.domain.error.WithdrawalNotAllowedException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import com.shop.backend.presentation.web.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtService.class})
class UserControllerTest {

    private static final String BODY = "{\"password\":\"pw-1234\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserService userService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private ClientIpResolver clientIpResolver;

    private final RateLimiter.Attempt attempt = org.mockito.Mockito.mock(RateLimiter.Attempt.class);

    private static User activeUser() {
        User user = new User("member01", null, "hash", Role.USER);
        ReflectionTestUtils.setField(user, "id", "user-1");
        return user;
    }

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("9.9.9.9");
        when(rateLimiter.acquireLogin("9.9.9.9")).thenReturn(attempt);
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_탈퇴하면_204이고_토큰의_사용자_id로_서비스를_호출하며_실패로_세지_않는다() throws Exception {
        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService).withdraw("user-1", "pw-1234");
        verify(attempt).release();
        verify(attempt, never()).countAndRelease();
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_비밀번호가_틀리면_로그인_실패와_같은_문구로_401이고_실패로_센다() throws Exception {
        doThrow(new InvalidCredentialsException()).when(userService).withdraw("user-1", "pw-1234");

        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("아이디 또는 비밀번호가 올바르지 않습니다."));

        verify(rateLimiter).acquireLogin("9.9.9.9");
        verify(attempt).countAndRelease();
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_비밀번호가_비었으면_400이고_실패로_세지_않지만_해제한다() throws Exception {
        doThrow(new ValidationException("비밀번호를 입력해주세요.")).when(userService).withdraw("user-1", "");

        mockMvc.perform(delete("/api/users/me").contentType("application/json").content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("비밀번호를 입력해주세요."));

        verify(attempt, never()).countAndRelease();
        verify(attempt).release();
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_본문이_없으면_400이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(delete("/api/users/me"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    @WithMockUser(username = "boss01", roles = "ADMIN")
    void 실패_관리자_계정은_비밀번호와_상관없이_403이고_실패로_세지_않는다() throws Exception {
        doThrow(new WithdrawalNotAllowedException()).when(userService).withdraw("boss01", "pw-1234");

        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("관리자·판매자 계정은 탈퇴할 수 없어요."));

        verify(attempt, never()).countAndRelease();
        verify(attempt).release();
    }

    @Test
    void 실패_로그인_없이_탈퇴하면_401이고_서비스와_레이트_리밋을_건드리지_않는다() throws Exception {
        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(userService, rateLimiter);
    }

    @Test
    void 실패_탈퇴한_사용자의_유효한_토큰은_인증되지_않아_401이다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1")).thenReturn(false); // withdrawn or unknown

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + jwtService.generateToken(activeUser()))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(userService);
    }

    @Test
    void 성공_활성_사용자의_유효한_토큰이면_인증되어_토큰의_사용자_id로_탈퇴를_호출한다() throws Exception {
        when(userRepository.existsByIdAndDeletedAtIsNull("user-1")).thenReturn(true);

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + jwtService.generateToken(activeUser()))
                        .contentType("application/json").content(BODY))
                .andExpect(status().isNoContent());

        verify(userService).withdraw("user-1", "pw-1234");
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_로그인과_같은_레이트_리밋이_걸리면_429와_Retry_After이고_탈퇴하지_않는다() throws Exception {
        when(rateLimiter.acquireLogin("9.9.9.9")).thenThrow(new TooManyRequestsException(300));

        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"));

        verifyNoInteractions(userService);
        verifyNoInteractions(attempt);
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_탈퇴_중_예상치_못한_오류면_500이고_실패로_세지_않지만_해제한다() throws Exception {
        doThrow(new IllegalStateException("boom")).when(userService).withdraw("user-1", "pw-1234");

        mockMvc.perform(delete("/api/users/me").contentType("application/json").content(BODY))
                .andExpect(status().isInternalServerError());

        verify(attempt, never()).countAndRelease();
        verify(attempt).release();
    }
}
