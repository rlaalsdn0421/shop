package com.shop.backend.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentAmountMismatchException;
import com.shop.backend.domain.error.PaymentChannelNotAllowedException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentNotConfirmedException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "user-1";
    private static final String STORE = "store-test";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentTransactions transactions;
    @Mock
    private WebhookSignatureVerifier verifier;

    private PaymentService service() {
        return service(STORE);
    }

    private PaymentService service(String storeId) {
        return service(storeId, false);
    }

    private PaymentService service(String storeId, boolean allowTest) {
        return new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), storeId, allowTest);
    }

    private Order order(String status, int total) {
        Order order = new Order(USER, "k", "h", "구매자", "010", "서울", total, 0, NOW);
        ReflectionTestUtils.setField(order, "id", ORDER);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private void givenOrder(String status) {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findByIdAndUserId(ORDER, USER)).thenReturn(Optional.of(order(status, 13_500)));
    }

    private static PaymentGateway.Payment payment(String status, long total) {
        return new PaymentGateway.Payment(ORDER, STORE, status, total, "KRW", "tx-1", "LIVE");
    }

    @Test
    void 성공_포트원이_결제완료와_일치하는_금액을_확인하면_결제완료로_바꾼다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.of(payment("PAID", 13_500)));
        when(transactions.applyPaid(ORDER, "tx-1")).thenReturn(Order.STATUS_PAID);

        assertThat(service().complete(ORDER, USER)).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 성공_이미_결제된_주문은_포트원을_부르지_않고_성공을_돌려준다() {
        givenOrder(Order.STATUS_PAID);

        assertThat(service().complete(ORDER, USER)).isEqualTo(Order.STATUS_PAID);

        verifyNoInteractions(transactions);
        verify(gateway, never()).find(anyString());
    }

    @Test
    void 성공_웹훅처럼_userId가_없으면_주문자_확인_없이_id로_찾는다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(Order.STATUS_PAID, 1000)));

        assertThat(service().complete(ORDER, null)).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 실패_남의_주문이나_없는_주문은_찾을_수_없다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findByIdAndUserId(ORDER, "other")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().complete(ORDER, "other")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service().complete(ORDER, " ")).isInstanceOf(OrderNotFoundException.class);
        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_포트원에_결제가_없거나_아직_완료가_아니면_상태를_바꾸지_않는다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.empty(), Optional.of(payment("READY", 13_500)));

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentNotConfirmedException.class);
        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentNotConfirmedException.class);
        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_금액_통화_주문id_스토어가_하나라도_다르면_거부한다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(
                Optional.of(payment("PAID", 13_499)),
                Optional.of(new PaymentGateway.Payment(ORDER, STORE, "PAID", 13_500L, "USD", "tx", "LIVE")),
                Optional.of(new PaymentGateway.Payment("other", STORE, "PAID", 13_500L, "KRW", "tx", "LIVE")),
                Optional.of(new PaymentGateway.Payment(ORDER, "other-store", "PAID", 13_500L, "KRW", "tx", "LIVE")),
                Optional.of(new PaymentGateway.Payment(ORDER, STORE, "PAID", null, "KRW", "tx", "LIVE")));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentAmountMismatchException.class);
        }
        verifyNoInteractions(transactions);
    }

    private static PaymentGateway.Payment onChannel(String channelType) {
        return new PaymentGateway.Payment(ORDER, STORE, "PAID", 13_500L, "KRW", "tx-1", channelType);
    }

    @Test
    void 성공_LIVE_채널_결제는_기본_설정에서도_확정된다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.of(onChannel("LIVE")));
        when(transactions.applyPaid(ORDER, "tx-1")).thenReturn(Order.STATUS_PAID);

        assertThat(service().complete(ORDER, USER)).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 실패_TEST_채널_결제는_기본_설정에서_거부하고_상태를_바꾸지_않는다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.of(onChannel("TEST")));

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentChannelNotAllowedException.class);
        verifyNoInteractions(transactions);
    }

    @Test
    void 성공_TEST_채널_결제는_허용_설정을_켰을_때만_확정된다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.of(onChannel("TEST")));
        when(transactions.applyPaid(ORDER, "tx-1")).thenReturn(Order.STATUS_PAID);

        assertThat(service(STORE, true).complete(ORDER, USER)).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 실패_채널_정보가_없거나_모르는_값이면_허용_설정과_상관없이_거부한다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenReturn(Optional.of(onChannel(null)), Optional.of(onChannel("SOMETHING")),
                Optional.of(onChannel(null)));

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentChannelNotAllowedException.class);
        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentChannelNotAllowedException.class);
        assertThatThrownBy(() -> service(STORE, true).complete(ORDER, USER)).isInstanceOf(PaymentChannelNotAllowedException.class);
        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_만료_뒤_결제됐는데_재고가_없으면_결제확인필요를_알린다() {
        givenOrder(Order.STATUS_EXPIRED);
        when(gateway.find(ORDER)).thenReturn(Optional.of(payment("PAID", 13_500)));
        when(transactions.applyPaid(ORDER, "tx-1")).thenReturn(Order.STATUS_PAYMENT_REVIEW);

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentNeedsReviewException.class);
    }

    @Test
    void 실패_이미_결제확인필요인_주문은_포트원을_부르지_않는다() {
        givenOrder(Order.STATUS_PAYMENT_REVIEW);

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentNeedsReviewException.class);
        verify(gateway, never()).find(anyString());
    }

    @Test
    void 실패_포트원_장애는_그대로_전달하고_상태를_바꾸지_않는다() {
        givenOrder(Order.STATUS_PENDING_PAYMENT);
        when(gateway.find(ORDER)).thenThrow(new PaymentProviderUnavailableException());

        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentProviderUnavailableException.class);
        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_결제_설정이_비어_있으면_아무것도_하지_않는다() {
        when(gateway.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service().complete(ORDER, USER)).isInstanceOf(PaymentNotConfiguredException.class);

        assertThatThrownBy(() -> service("").complete(ORDER, USER)).isInstanceOf(PaymentNotConfiguredException.class);
        verifyNoInteractions(orderRepository, transactions);
    }

    private static final String PAID_EVENT = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"t\",\"data\":{\"paymentId\":\""
            + ORDER + "\",\"storeId\":\"s\",\"transactionId\":\"t\",\"extra\":1},\"unknown\":true}";

    @Test
    void 성공_결제완료_웹훅은_같은_확정_로직을_주문자_확인_없이_탄다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT, 13_500)));
        when(gateway.find(ORDER)).thenReturn(Optional.of(payment("PAID", 13_500)));
        when(transactions.applyPaid(ORDER, "tx-1")).thenReturn(Order.STATUS_PAID);

        service().handleWebhook("id", "ts", "sig", PAID_EVENT);

        verify(verifier).verify("id", "ts", "sig", PAID_EVENT);
        verify(transactions).applyPaid(ORDER, "tx-1");
    }

    @Test
    void 성공_다른_타입_알_수_없는_결제id_결제id_없음은_무시한다() {
        service().handleWebhook("id", "ts", "sig", "{\"type\":\"Transaction.VirtualAccountIssued\",\"data\":{\"paymentId\":\"x\"}}");
        service().handleWebhook("id", "ts", "sig", "{\"type\":\"Transaction.Paid\",\"data\":{}}");
        when(orderRepository.existsById("not-ours")).thenReturn(false);
        service().handleWebhook("id", "ts", "sig", "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"not-ours\"}}");

        verifyNoInteractions(gateway, transactions);
    }

    @Test
    void 성공_웹훅의_타입이_다르면_data가_이상해도_무시한다() {
        service().handleWebhook("id", "ts", "sig", "{\"type\":\"Transaction.Cancelled\",\"data\":\"weird\"}");
        service().handleWebhook("id", "ts", "sig", "{\"type\":123,\"data\":{\"paymentId\":\"x\"}}");
        service().handleWebhook("id", "ts", "sig", "{\"data\":{\"paymentId\":\"x\"}}");
        service().handleWebhook("id", "ts", "sig", "[]");
        service().handleWebhook("id", "ts", "sig", "null");

        verifyNoInteractions(orderRepository, gateway, transactions);
    }

    @Test
    void 성공_결제완료_타입이어도_data나_paymentId_모양이_이상하면_무시한다() {
        String[] odd = {
                "{\"type\":\"Transaction.Paid\"}",
                "{\"type\":\"Transaction.Paid\",\"data\":[]}",
                "{\"type\":\"Transaction.Paid\",\"data\":\"x\"}",
                "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":5}}",
                "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":{\"a\":1}}}",
                "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\" \"}}"};
        for (String body : odd) {
            service().handleWebhook("id", "ts", "sig", body);
        }

        verifyNoInteractions(orderRepository, gateway, transactions);
    }

    @Test
    void 성공_웹훅_사이에_사라진_주문과_허용되지_않은_채널은_정상_응답한다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.empty(),
                Optional.of(order(Order.STATUS_PENDING_PAYMENT, 13_500)));
        when(gateway.find(ORDER)).thenReturn(Optional.of(onChannel("TEST")));

        service().handleWebhook("id", "ts", "sig", PAID_EVENT); // order vanished
        service().handleWebhook("id", "ts", "sig", PAID_EVENT); // TEST channel

        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_웹훅의_결제_미확인과_설정_없음은_전달해_재시도하게_한다() {
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT, 13_500)));
        when(gateway.find(ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().handleWebhook("id", "ts", "sig", PAID_EVENT))
                .isInstanceOf(PaymentNotConfirmedException.class);
        assertThatThrownBy(() -> service("").handleWebhook("id", "ts", "sig", PAID_EVENT))
                .isInstanceOf(PaymentNotConfiguredException.class);
    }

    @Test
    void 성공_금액불일치나_확인필요는_재시도해도_소용없어_정상_응답한다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT, 13_500)));
        when(gateway.find(ORDER)).thenReturn(Optional.of(payment("PAID", 1)));

        service().handleWebhook("id", "ts", "sig", PAID_EVENT); // does not throw

        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_웹훅_포트원_장애는_전달해_재시도하게_한다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.existsById(ORDER)).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT, 13_500)));
        when(gateway.find(ORDER)).thenThrow(new PaymentProviderUnavailableException());

        assertThatThrownBy(() -> service().handleWebhook("id", "ts", "sig", PAID_EVENT))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    void 실패_서명이_틀리거나_본문이_JSON이_아니면_거부한다() {
        doThrow(new ValidationException("bad")).when(verifier).verify(any(), any(), any(), any());
        assertThatThrownBy(() -> service().handleWebhook("id", "ts", "sig", PAID_EVENT)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(orderRepository, gateway, transactions);
    }

    @Test
    void 실패_서명은_맞지만_JSON이_아니면_400이다() {
        assertThatThrownBy(() -> service().handleWebhook("id", "ts", "sig", "not json")).isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_만료_정리는_후보마다_따로_처리하고_실제_만료된_수만_센다() {
        when(orderRepository.findExpiredPendingIds(any(Instant.class), any(Pageable.class))).thenReturn(List.of("a", "b", "c", "d"));
        when(transactions.expireOne("a")).thenReturn(true);
        when(transactions.expireOne("b")).thenReturn(false); // paid meanwhile / locked
        when(transactions.expireOne("c")).thenThrow(new IllegalStateException("lock"));
        when(transactions.expireOne("d")).thenReturn(true);

        assertThat(service().expirePending()).isEqualTo(2);
    }

    @Test
    void 성공_만료_정리는_한_번에_최대_100건만_가져온다() {
        when(orderRepository.findExpiredPendingIds(any(Instant.class), any(Pageable.class))).thenReturn(List.of());

        assertThat(service().expirePending()).isZero();

        org.mockito.ArgumentCaptor<Pageable> page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findExpiredPendingIds(any(Instant.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
    }
}
