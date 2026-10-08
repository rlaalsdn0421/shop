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
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class})
class AuthControllerTest {

    private static final String REGISTER_BODY = """
            {"username":"user01","email":"a@a.com","password":"password1","birthDate":"2000-05-05"}
            """;
    private static final String LOGIN_BODY = """
            {"username":"seller01","password":"password1","birthDate":"2000-05-05"}
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
        when(authService.register(any(), any(), any(), any())).thenReturn(user);

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
        when(authService.register(any(), any(), any(), any())).thenThrow(new DuplicateUsernameException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 아이디입니다."));

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_이미_등록된_이메일이면_400을_반환하고_시도는_세어진다() throws Exception {
        when(authService.register(any(), any(), any(), any())).thenThrow(new DuplicateEmailException());

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 사용 중인 이메일입니다."));

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_검증에_실패한_가입도_시도로_세어진다() throws Exception {
        when(authService.register(any(), any(), any(), any())).thenThrow(new ValidationException("비밀번호 규칙 위반"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(REGISTER_BODY))
                .andExpect(status().isBadRequest());

        verify(attempt).countAndRelease();
    }

    @Test
    void 실패_가입_중_예상치_못한_오류가_나도_시도가_세어지고_해제된다() throws Exception {
        when(authService.register(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

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

    // ---- 생년월일 ----

    @Test
    void 성공_가입하면_생년월일을_서비스에_그대로_넘긴다() throws Exception {
        User user = new User("user01", "a@a.com", "hashed", Role.USER, LocalDate.of(2000, 5, 5));
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(authService.register(any(), any(), any(), any())).thenReturn(user);

        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_BODY))
                .andExpect(status().isOk());

        verify(authService).register(eq("user01"), eq("a@a.com"), eq("password1"), eq(LocalDate.of(2000, 5, 5)));
    }

    @Test
    void 성공_가입_응답에는_생년월일이_들어_있지_않다() throws Exception {
        User user = new User("user01", "a@a.com", "hashed", Role.USER, LocalDate.of(2000, 5, 5));
        ReflectionTestUtils.setField(user, "id", "user-1");
        when(authService.register(any(), any(), any(), any())).thenReturn(user);

        String body = mockMvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_BODY))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("{\"id\":\"user-1\"}");
        assertThat(body).doesNotContain("2000").doesNotContain("birth");
    }

    @Test
    void 성공_로그인_응답에는_생년월일이_들어_있지_않다() throws Exception {
        when(authService.login(any(), any())).thenReturn(new AuthService.LoginResult("token123", "USER", "user01"));

        String body = mockMvc.perform(post("/api/auth/login").contentType("application/json").content(LOGIN_BODY))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("birth");
        assertThat(body).isEqualTo("{\"token\":\"token123\",\"role\":\"USER\",\"username\":\"user01\"}");
    }

    @Test
    void 실패_생년월일이_없으면_400과_안내_메시지를_반환한다() throws Exception {
        when(authService.register(any(), any(), any(), eq(null)))
                .thenThrow(new ValidationException("생년월일을 입력해주세요."));

        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("생년월일을 입력해주세요."));
    }

    @Test
    void 실패_만_14세_미만이면_400과_안내_메시지를_반환한다() throws Exception {
        when(authService.register(any(), any(), any(), any()))
                .thenThrow(new ValidationException("만 14세 이상만 가입할 수 있어요."));

        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("만 14세 이상만 가입할 수 있어요."));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2000/01/01", "abc", "20000101", "2000-13-01", "2000-02-30"})
    void 실패_생년월일_형식이_잘못되면_400이고_서비스는_호출되지_않으며_응답에_입력값이_없다(String birthDate) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\","
                                + "\"birthDate\":\"" + birthDate + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문을 읽을 수 없습니다."));

        verifyNoInteractions(authService);
    }

    @Test
    void 성공_빈_문자열_생년월일은_없는_값으로_서비스에_넘어가_거절된다() throws Exception {
        when(authService.register(any(), any(), any(), eq(null)))
                .thenThrow(new ValidationException("생년월일을 입력해주세요."));

        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\",\"birthDate\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("생년월일을 입력해주세요."));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "12345", "[2000,5,5]", "\"2000-05-05T10:00:00\"", "\"2000-05-05T23:30:00Z\"", "\" 2000-05-05\"",
            "\"2000-05-05 \"", "true", "{\"year\":2000}"})
    void 실패_ISO_문자열이_아닌_생년월일은_400이고_서비스는_호출되지_않으며_응답에_입력값이_없다(String rawJsonValue) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\","
                                + "\"birthDate\":" + rawJsonValue + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문을 읽을 수 없습니다."))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("2000").doesNotContain("12345");
        verifyNoInteractions(authService);
    }

    // ---- 로그에 개인정보가 남지 않는지 ----

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(
            "com.shop.backend.presentation.exception.GlobalExceptionHandler");
    private Level handlerLevelBefore;

    private void captureLogs() {
        handlerLevelBefore = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        logEvents.start();
        rootLogger.addAppender(logEvents);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logEvents);
        logEvents.stop();
        handlerLogger.setLevel(handlerLevelBefore);
    }

    @Test
    void 실패_무결성_오류는_일반_500을_반환하고_로그에는_메시지_해시_생년월일이_남지_않는다() throws Exception {
        captureLogs();
        String row = "Failing row contains (x, user, a@b.c, HASH, USER, 2000-05-05)";
        var hibernateCause = new org.hibernate.exception.ConstraintViolationException(
                row, new SQLException(row, "23514"), "users_birth_date_check");
        when(authService.register(any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException(row, hibernateCause));

        mockMvc.perform(post("/api/auth/register").contentType("application/json").content(REGISTER_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("요청 처리 중 오류가 발생했습니다."));

        String logged = loggedText();
        assertThat(logged).contains("DataIntegrityViolationException", "23514", "users_birth_date_check");
        assertThat(logged).doesNotContain("Failing row").doesNotContain("HASH")
                .doesNotContain("2000-05-05").doesNotContain("a@b.c");
        assertThat(logEvents.list).allMatch(e -> e.getThrowableProxy() == null);
    }

    @Test
    void 실패_읽을_수_없는_본문은_원인_클래스명만_로그에_남기고_입력값은_남기지_않는다() throws Exception {
        captureLogs();

        mockMvc.perform(post("/api/auth/register").contentType("application/json")
                        .content("{\"username\":\"user01\",\"email\":\"a@a.com\",\"password\":\"password1\","
                                + "\"birthDate\":\"1999/12/31\"}"))
                .andExpect(status().isBadRequest());

        String logged = loggedText();
        assertThat(logged).contains("MismatchedInputException");
        assertThat(logged).doesNotContain("1999").doesNotContain("yyyy-MM-dd");
    }

    private String loggedText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logEvents.list) {
            sb.append(e.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
