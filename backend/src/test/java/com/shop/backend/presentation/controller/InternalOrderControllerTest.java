package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.PaymentService;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalOrderController.class)
@Import({SecurityConfig.class, JwtService.class})
@TestPropertySource(properties = "internal.token=test-only-token-with-at-least-32-chars")
class InternalOrderControllerTest {

    private static final String URL = "/api/internal/orders/expire-pending";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private PaymentService paymentService;

    @Test
    void 성공_올바른_토큰이면_정리하고_건수를_돌려준다() throws Exception {
        when(paymentService.expirePending()).thenReturn(3);

        mockMvc.perform(post(URL).header("X-Internal-Token", "test-only-token-with-at-least-32-chars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(3));
    }

    @Test
    void 실패_토큰이_없거나_틀리면_정리하지_않는다() throws Exception {
        mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).header("X-Internal-Token", "test-only-token-with-at-least-32-chars-x")).andExpect(status().isUnauthorized());

        verifyNoInteractions(paymentService);
    }
}
