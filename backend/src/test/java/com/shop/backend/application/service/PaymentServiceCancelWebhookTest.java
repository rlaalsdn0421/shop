package com.shop.backend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    void 성공_이미_취소된_주문의_취소_웹훅은_아무_로그도_상태_변경도_없이_끝난다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED, Order.CancelOutcome.ALREADY_FINAL));
        ListAppender<ILoggingEvent> logs = captureLogs();

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verify(transactions).applyCancelled(ORDER);
        verifyNoMoreInteractions(transactions); // no begin/revert/paid, so no stock or state work of ours
        verify(gateway, never()).cancel(anyString(), anyString(), any());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_결제_전_주문의_취소_웹훅은_상태를_바꾸지_않고_INFO만_남긴다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_EXPIRED, Order.CancelOutcome.IGNORED));
        ListAppender<ILoggingEvent> logs = captureLogs();

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verify(transactions).applyCancelled(ORDER);
        verifyNoMoreInteractions(transactions);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("never paid").contains(ORDER).contains(Order.STATUS_EXPIRED);
        });
    }

    @Test
    void 성공_배송_후_주문의_취소_웹훅은_환불됨으로_마무리하고_재고_복구_없음을_INFO로_남긴다() {
        ourOrderAndProvider(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_REFUNDED, Order.CancelOutcome.NO_STOCK_CHANGE));
        ListAppender<ILoggingEvent> logs = captureLogs();

        service(STORE).handleWebhook("id", "ts", "sig", event("Transaction.Cancelled"));

        verify(transactions).applyCancelled(ORDER);
        verifyNoMoreInteractions(transactions);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("stock not restored").contains(ORDER);
        });
    }

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(PaymentService.class);
    private ListAppender<ILoggingEvent> appender;

    private ListAppender<ILoggingEvent> captureLogs() {
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void detachLogs() {
        if (appender != null) {
            serviceLogger.detachAppender(appender);
        }
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
