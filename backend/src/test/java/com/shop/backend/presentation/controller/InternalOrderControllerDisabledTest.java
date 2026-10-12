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
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** INTERNAL_TOKEN unset (the default): the endpoint does not exist, even for an empty or "matching" empty header. */
@WebMvcTest(InternalOrderController.class)
@Import({SecurityConfig.class, JwtService.class})
class InternalOrderControllerDisabledTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private PaymentService paymentService;

    @Test
    void 실패_토큰_설정이_비어_있으면_404이다() throws Exception {
        mockMvc.perform(post("/api/internal/orders/expire-pending")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/internal/orders/expire-pending").header("X-Internal-Token", ""))
                .andExpect(status().isNotFound());

        verifyNoInteractions(paymentService);
    }
}
