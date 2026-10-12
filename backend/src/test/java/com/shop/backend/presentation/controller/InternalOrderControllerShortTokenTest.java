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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A token under 32 characters is too weak to trust: the endpoint stays off even when the caller sends that exact token. */
@WebMvcTest(InternalOrderController.class)
@Import({SecurityConfig.class, JwtService.class})
@TestPropertySource(properties = "internal.token=0123456789012345678901234567890")
class InternalOrderControllerShortTokenTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private PaymentService paymentService;

    @Test
    void 실패_32자_미만_토큰은_맞게_보내도_404이다() throws Exception {
        mockMvc.perform(post("/api/internal/orders/expire-pending").header("X-Internal-Token", "0123456789012345678901234567890"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(paymentService);
    }
}
