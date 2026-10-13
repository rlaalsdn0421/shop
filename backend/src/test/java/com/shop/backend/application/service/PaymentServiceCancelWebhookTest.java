package com.shop.backend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Transaction.Cancelled / Transaction.PartialCancelled webhooks (docs/refund-design.md section 3). */
@ExtendWith(MockitoExtension.class)
class PaymentServiceCancelWebhookTest {

    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String STORE = "store-test";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentTransactions transactions;
    @Mock
    private WebhookSignatureVerifier verifier;

    private static String event(String type) {
        return "{\"type\":\"" + type + "\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\"" + ORDER
                + "\",\"storeId\":\"s\",\"transactionId\":\"t\",\"cancellationId\":\"c\"}}";
    }

    private PaymentService service(String storeId) {
        return new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), storeId, false);
    }

    private static PaymentGateway.Payment payment(String status) {
        return new PaymentGateway.Payment(ORDER, STORE, status, 13_500L, "KRW", "tx-1", "LIVE");
    }

    private void ourOrderAndProvider(PaymentGateway.Payment payment) {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(gateway.find(ORDER)).thenReturn(Optional.ofNullable(payment));
    }

    @Test
    void 성공_전액_취소_웹훅은_포트원에서_다시_조회해_취소됨이면_주문에_반영한다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED, Order.CancelOutcome.RESTORE_STOCK));

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verify(verifier).verify("id", "ts", "sig", event("Transaction.Cancelled"));
        verify(transactions).applyCancelled(ORDER);
    }

    @Test
    void 성공_이미_취소된_주문과_결제_전_주문의_취소_웹훅은_정상_응답한다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED, Order.CancelOutcome.ALREADY_FINAL),
                new PaymentTransactions.CancelApplied(Order.STATUS_EXPIRED, Order.CancelOutcome.IGNORED),
                new PaymentTransactions.CancelApplied(Order.STATUS_REFUNDED, Order.CancelOutcome.NO_STOCK_CHANGE));

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));
    }

    @Test
    void 실패_포트원이_취소됨으로_보고하지_않으면_웹훅_본문을_믿지_않고_주문을_바꾸지_않는다() {
        ourOrderAndProvider(payment("PAID"));

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verify(transactions, never()).applyCancelled(anyString());
    }

    @Test
    void 실패_부분_취소_상태나_결제_없음이면_주문을_바꾸지_않는다() {
        ourOrderAndProvider(payment("PARTIAL_CANCELLED"));
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        when(gateway.find(ORDER)).thenReturn(Optional.empty());
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_다른_상점_또는_다른_id의_결제는_주문을_바꾸지_않는다() {
        ourOrderAndProvider(new PaymentGateway.Payment(ORDER, "other-store", "CANCELLED", 13_500L, "KRW", "tx", "LIVE"));
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        when(gateway.find(ORDER)).thenReturn(Optional.of(
                new PaymentGateway.Payment("other-order", STORE, "CANCELLED", 13_500L, "KRW", "tx", "LIVE")));
        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verifyNoInteractions(transactions);
    }

    @Test
    void 성공_우리_주문이_아닌_결제의_취소_웹훅은_포트원을_부르지_않고_무시한다() {
        when(orderRepository.existsById(ORDER)).thenReturn(false);

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verifyNoInteractions(gateway, transactions);
    }

    @Test
    void 성공_취소_웹훅_사이에_사라진_주문은_정상_응답한다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenThrow(new OrderNotFoundException());

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled")); // does not throw
    }

    @Test
    void 실패_취소_웹훅에서_포트원_장애와_설정_없음은_전달해_재시도하게_한다() {
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.find(ORDER)).thenThrow(new PaymentProviderUnavailableException());

        assertThatThrownBy(() -> service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled")))
                .isInstanceOf(PaymentProviderUnavailableException.class);
        assertThatThrownBy(() -> service("").handleWebhook("id", "ts", "sig", event("Transaction.Cancelled")))
                .isInstanceOf(PaymentNotConfiguredException.class);
    }

    @Test
    void 성공_부분_취소_웹훅은_ERROR_로그만_남기고_주문과_포트원은_건드리지_않는다() {
        when(orderRepository.existsById(ORDER)).thenReturn(true);

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.PartialCancelled"));

        verifyNoInteractions(gateway, transactions);
    }

    @Test
    void 성공_우리_주문이_아닌_부분_취소_웹훅은_무시한다() {
        when(orderRepository.existsById(ORDER)).thenReturn(false);

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.PartialCancelled"));

        verifyNoInteractions(gateway, transactions);
    }
}
