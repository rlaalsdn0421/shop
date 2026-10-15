package com.shop.backend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An order that was paid and has moved on (CANCELING, CANCELLED, SHIPPED, DELIVERED, REFUNDED) must never be
 * "confirmed" again, by the customer's complete call or by a late/retried Transaction.Paid webhook.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServicePostPaymentTest {

    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "user-1";
    private static final String STORE = "store-test";
    private static final List<String> POST_PAYMENT = List.of(Order.STATUS_CANCELING, Order.STATUS_CANCELLED,
            Order.STATUS_SHIPPED, Order.STATUS_DELIVERED, Order.STATUS_REFUNDED);
    private static final String PAID_EVENT = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-06-15T12:00:00Z\","
            + "\"data\":{\"paymentId\":\"" + ORDER + "\",\"storeId\":\"s\",\"transactionId\":\"t\"}}";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentTransactions transactions;
    @Mock
    private WebhookSignatureVerifier verifier;

    private PaymentService service() {
        return new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), STORE, false);
    }

    private static Order order(String status) {
        Order order = new Order(USER, "k", "h", "구매자", "010", "서울", 13_500, 0, Instant.parse("2026-06-15T12:00:00Z"));
        ReflectionTestUtils.setField(order, "id", ORDER);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Test
    void 성공_결제_이후_상태의_주문은_complete가_포트원을_부르지_않고_현재_상태를_그대로_돌려준다() {
        when(gateway.isConfigured()).thenReturn(true);
        for (String status : POST_PAYMENT) {
            when(orderRepository.findByIdAndUserId(ORDER, USER)).thenReturn(Optional.of(order(status)));

            assertThat(service().complete(ORDER, USER)).as(status).isEqualTo(status);
        }

        verifyNoInteractions(transactions); // applyPaid and everything else
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).find(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 성공_서명된_결제완료_웹훅이_늦게_다시_와도_결제_이후_주문은_바뀌지_않고_정상_응답한다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        for (String status : POST_PAYMENT) {
            when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(status)));

            service().handleWebhook("id", "ts", "sig", PAID_EVENT); // returns normally: the provider sees 200

            verify(verifier, org.mockito.Mockito.atLeastOnce()).verify("id", "ts", "sig", PAID_EVENT);
        }

        verifyNoInteractions(transactions);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).find(org.mockito.ArgumentMatchers.anyString());
    }
}
