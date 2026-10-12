package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.PaymentService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentAmountMismatchException;
import com.shop.backend.domain.error.PaymentChannelNotAllowedException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentNotConfirmedException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtService.class})
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private PaymentService paymentService;

    private static final String COMPLETE = "/api/orders/o1/payment/complete";

    @Test
    @WithMockUser(username = "user-1")
    void 성공_결제_확정은_주문자_id로_서비스를_부르고_상태를_돌려준다() throws Exception {
        when(paymentService.complete("o1", "user-1")).thenReturn(Order.STATUS_PAID);

        mockMvc.perform(post(COMPLETE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void 실패_로그인_없이_결제_확정하면_401이다() throws Exception {
        mockMvc.perform(post(COMPLETE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(paymentService);
    }

    @Test
    @WithMockUser
    void 실패_없는_주문이나_남의_주문은_404이다() throws Exception {
        when(paymentService.complete(any(), any())).thenThrow(new OrderNotFoundException());

        mockMvc.perform(post(COMPLETE)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void 실패_결제가_아직_확인되지_않으면_409_코드를_준다() throws Exception {
        when(paymentService.complete(any(), any())).thenThrow(new PaymentNotConfirmedException());

        mockMvc.perform(post(COMPLETE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIRMED"))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @WithMockUser
    void 실패_금액불일치와_확인필요도_409이다() throws Exception {
        when(paymentService.complete(any(), any())).thenThrow(new PaymentAmountMismatchException())
                .thenThrow(new PaymentNeedsReviewException());

        mockMvc.perform(post(COMPLETE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        mockMvc.perform(post(COMPLETE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NEEDS_REVIEW"));
    }

    @Test
    @WithMockUser
    void 실패_포트원_장애와_설정_없음은_503이다() throws Exception {
        when(paymentService.complete(any(), any())).thenThrow(new PaymentProviderUnavailableException())
                .thenThrow(new PaymentNotConfiguredException());

        mockMvc.perform(post(COMPLETE)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value("결제 확인이 지연되고 있어요. 잠시 뒤 다시 확인해 주세요."));
        mockMvc.perform(post(COMPLETE)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"));
    }

    @Test
    void 성공_웹훅은_로그인_없이_원문_본문과_헤더를_서비스에_넘긴다() throws Exception {
        String body = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"o1\"}}";

        mockMvc.perform(post("/api/payments/webhook")
                        .header("webhook-id", "msg_1").header("webhook-timestamp", "1").header("webhook-signature", "v1,x")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());

        verify(paymentService).handleWebhook("msg_1", "1", "v1,x", body);
    }

    @Test
    void 실패_웹훅_서명이_틀리면_400이다() throws Exception {
        doThrow(new ValidationException("웹훅 서명이 올바르지 않아요.")).when(paymentService)
                .handleWebhook(any(), any(), any(), any());

        mockMvc.perform(webhook("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void 실패_웹훅_헤더가_하나라도_없으면_본문을_읽기_전에_400이다() throws Exception {
        String[] names = {"webhook-id", "webhook-timestamp", "webhook-signature"};
        for (String missing : names) {
            var request = post("/api/payments/webhook").contentType("application/json").content("{}");
            for (String name : names) {
                if (!name.equals(missing)) {
                    request.header(name, "x");
                }
            }
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/payments/webhook").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    @Test
    void 실패_웹훅_본문이_64KB를_넘으면_서비스에_넘기지_않고_413이다() throws Exception {
        String big = "x".repeat(64 * 1024 + 1);

        mockMvc.perform(webhook(big)).andExpect(status().isPayloadTooLarge());
        // Content-Length lies (small) as it could with a chunked upload: the capped read still stops it
        mockMvc.perform(webhook(big).header("Content-Length", "10")).andExpect(status().isPayloadTooLarge());

        verifyNoInteractions(paymentService);
    }

    @Test
    void 성공_웹훅_본문이_정확히_64KB면_받는다() throws Exception {
        String exact = "x".repeat(64 * 1024);

        mockMvc.perform(webhook(exact)).andExpect(status().isOk());

        verify(paymentService).handleWebhook("msg_1", "1", "v1,x", exact);
    }

    @Test
    void 실패_웹훅_중_포트원_장애는_5xx로_돌려_재시도하게_한다() throws Exception {
        doThrow(new PaymentProviderUnavailableException()).when(paymentService).handleWebhook(any(), any(), any(), any());

        mockMvc.perform(webhook("{}")).andExpect(status().isServiceUnavailable());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(String body) {
        return post("/api/payments/webhook")
                .header("webhook-id", "msg_1").header("webhook-timestamp", "1").header("webhook-signature", "v1,x")
                .contentType("application/json").content(body);
    }

    @Test
    @WithMockUser
    void 실패_허용되지_않은_결제_채널은_409_코드를_준다() throws Exception {
        when(paymentService.complete(any(), any())).thenThrow(new PaymentChannelNotAllowedException());

        mockMvc.perform(post(COMPLETE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CHANNEL_NOT_ALLOWED"));
    }
}
