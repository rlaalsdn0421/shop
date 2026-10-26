package com.shop.backend.presentation.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shop.backend.domain.error.DuplicateReviewException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An unmatched route is a 404 without an ERROR log (it used to fall into the catch-all: 500 + stack trace), and the
 * neighbouring mappings stay as they were. The stub controller throws one exception per status so this test does
 * not depend on any real controller.
 */
@WebMvcTest(GlobalExceptionHandlerTest.ThrowingController.class)
@Import({SecurityConfig.class, JwtService.class, GlobalExceptionHandlerTest.ThrowingController.class})
class GlobalExceptionHandlerTest {

    @RestController
    static class ThrowingController {
        @GetMapping("/api/test/400")
        String badRequest() {
            throw new ValidationException("잘못된 값");
        }

        @GetMapping("/api/test/401")
        String unauthorized() {
            throw new InvalidCredentialsException();
        }

        @GetMapping("/api/test/409")
        String conflict() {
            throw new DuplicateReviewException();
        }

        @GetMapping("/api/test/500")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    private final ListAppender<ILoggingEvent> logEvents = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void captureLogs() {
        logEvents.start();
        rootLogger.addAppender(logEvents);
    }

    @AfterEach
    void releaseLogs() {
        rootLogger.detachAppender(logEvents);
        logEvents.stop();
    }

    @Test
    void 모르는_주소는_404이고_ERROR_로그가_없다() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("요청한 주소를 찾을 수 없습니다."));

        assertThat(logEvents.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void 모르는_주소는_메서드와_끝_슬래시가_달라도_404이다() throws Exception {
        mockMvc.perform(put("/api/wishlist/a/b")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/test/400/")).andExpect(status().isNotFound());
    }

    @Test
    void 기존_매핑은_그대로이다() throws Exception {
        mockMvc.perform(get("/api/test/400"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("잘못된 값"));
        mockMvc.perform(get("/api/test/401")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/test/409")).andExpect(status().isConflict());
        mockMvc.perform(get("/api/test/500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("요청 처리 중 오류가 발생했습니다."));

        // the real 500 is still logged at ERROR, so the 404 change did not silence it
        assertThat(logEvents.list).anyMatch(e -> e.getLevel() == Level.ERROR);
    }
}
