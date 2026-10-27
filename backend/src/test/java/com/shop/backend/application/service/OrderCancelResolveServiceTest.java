package com.shop.backend.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.CancelAlreadyDoneException;
import com.shop.backend.domain.error.CancelProviderStateException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seller's "check cancel status" / "abandon cancel" for a stuck CANCELING order (docs/cancel-resolve-design.md
 * sections 2 and 3). The PaymentService is the REAL one (only its collaborators are mocks) so the store/id verification
 * shared with the cancel webhook is exercised, not stubbed away.
 */
@ExtendWith(MockitoExtension.class)
class OrderCancelResolveServiceTest {

    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String STORE = "store-test";
    private static final String ACTOR = "seller-user-1";

    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentTransactions transactions;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private WebhookSignatureVerifier verifier;

    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OrderCancelService.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    private OrderCancelService service() {
        return service(STORE);
    }

    private OrderCancelService service(String storeId) {
        PaymentService paymentService = new PaymentService(orderRepository, gateway, transactions, verifier,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), storeId, false);
        return new OrderCancelService(gateway, transactions, orderRepository, paymentService);
    }

    private static Order order(String status) {
        Order order = new Order("user-1", "k", "h", "홍길동", "010-1234-5678", "서울시 어딘가 1", 13_500, 3_500);
        ReflectionTestUtils.setField(order, "id", ORDER);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private static PaymentGateway.Payment payment(String status) {
        return new PaymentGateway.Payment(ORDER, STORE, status, 13_500L, "KRW", "tx-1", "LIVE");
    }

    private void configuredWithOrder(String status) {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(status)));
    }

    private void providerSays(PaymentGateway.Payment payment) {
        when(gateway.find(ORDER)).thenReturn(Optional.ofNullable(payment));
    }

    private static CancelAppliedBuilder applied(String status) {
        return new CancelAppliedBuilder(status);
    }

    private record CancelAppliedBuilder(String status) {
        PaymentTransactions.CancelApplied withStock() {
            return new PaymentTransactions.CancelApplied(status, Order.CancelOutcome.RESTORE_STOCK);
        }

        PaymentTransactions.CancelApplied noStock() {
            return new PaymentTransactions.CancelApplied(status, Order.CancelOutcome.NO_STOCK_CHANGE);
        }
    }

    private void assertNothingChanged() {
        verify(transactions, never()).applyCancelled(anyString());
        verify(transactions, never()).revertCancel(anyString());
        verify(transactions, never()).beginCancel(anyString(), any(), any());
        verify(gateway, never()).cancel(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ check: guards

    @Test
    void 실패_확인_결제_설정이_없으면_설정_없음이고_주문도_결제사도_건드리지_않는다() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service().checkCancelByAdmin(ORDER, ACTOR))
                .isInstanceOf(PaymentNotConfiguredException.class);

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_확인_없는_주문은_404이고_결제사를_부르지_않는다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().checkCancelByAdmin(ORDER, ACTOR)).isInstanceOf(OrderNotFoundException.class);

        verify(gateway, never()).find(anyString());
        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_확인_이미_취소됨_환불됨_주문은_결제사를_부르지_않고_그_상태를_그대로_돌려준다() {
        for (String done : List.of(Order.STATUS_CANCELLED, Order.STATUS_REFUNDED)) {
            when(gateway.isConfigured()).thenReturn(true);
            when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(done)));

            OrderCancelService.CancelResolution result = service().checkCancelByAdmin(ORDER, ACTOR);

            assertThat(result).isEqualTo(new OrderCancelService.CancelResolution(done, null)); // provider not asked: no status
        }

        verify(gateway, never()).find(anyString());
        assertNothingChanged();
    }

    @Test
    void 실패_확인_취소중이_아닌_상태는_409_INVALID_ORDER_STATE이고_결제사도_주문도_건드리지_않는다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_PAID, Order.STATUS_EXPIRED,
                Order.STATUS_PAYMENT_REVIEW, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED)) {
            when(gateway.isConfigured()).thenReturn(true);
            when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(status)));

            assertThatThrownBy(() -> service().checkCancelByAdmin(ORDER, ACTOR)).as(status)
                    .isInstanceOfSatisfying(InvalidOrderStateException.class,
                            e -> assertThat(e.getCode()).isEqualTo("INVALID_ORDER_STATE"));
        }

        verify(gateway, never()).find(anyString());
        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    // ------------------------------------------------------------------ check: provider answers

    @Test
    void 성공_확인_결제사가_전액_취소됨이면_마무리를_한_번만_부르고_새_상태와_결제사_상태를_돌려준다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(applied(Order.STATUS_CANCELLED).withStock());

        OrderCancelService.CancelResolution result = service().checkCancelByAdmin(ORDER, ACTOR);

        assertThat(result).isEqualTo(new OrderCancelService.CancelResolution("CANCELLED", "CANCELLED"));
        verify(transactions, org.mockito.Mockito.times(1)).applyCancelled(ORDER);
        verify(transactions, never()).revertCancel(anyString());
    }

    @Test
    void 성공_확인_배송_후_출신_주문은_마무리가_돌려준_환불됨을_그대로_돌려준다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(applied(Order.STATUS_REFUNDED).noStock());

        assertThat(service().checkCancelByAdmin(ORDER, ACTOR).status()).isEqualTo("REFUNDED");
    }

    @Test
    void 성공_확인_결제사_조회는_트랜잭션_호출보다_앞서고_마무리는_조회_뒤에_한다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(applied(Order.STATUS_CANCELLED).withStock());

        service().checkCancelByAdmin(ORDER, ACTOR);

        InOrder inOrder = inOrder(orderRepository, gateway, transactions);
        inOrder.verify(orderRepository).findById(ORDER);
        inOrder.verify(gateway).find(ORDER);
        inOrder.verify(transactions).applyCancelled(ORDER);
    }

    @Test
    void 성공_확인_결제사가_취소됨이_아니면_아무것도_바꾸지_않고_결제사_상태_문자열을_그대로_돌려준다() {
        for (String providerStatus : List.of("PAID", "PARTIAL_CANCELLED", "READY", "FAILED", "PAY_PENDING",
                "VIRTUAL_ACCOUNT_ISSUED", "SOMETHING_NEW")) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(providerStatus));

            assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                    .as(providerStatus).isEqualTo(new OrderCancelService.CancelResolution("CANCELING", providerStatus));
        }

        assertNothingChanged();
    }

    @Test
    void 성공_확인_결제사에_그런_결제가_없으면_NOT_FOUND를_알리고_아무것도_바꾸지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(null);

        assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                .isEqualTo(new OrderCancelService.CancelResolution("CANCELING", "NOT_FOUND"));

        assertNothingChanged();
    }

    @Test
    void 성공_확인_결제는_있는데_상태가_null이거나_비었으면_UNKNOWN으로_알리고_NOT_FOUND와_구분하며_아무것도_바꾸지_않는다() {
        for (String blank : new String[]{null, "", "   "}) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(blank));

            assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                    .as("[%s]", blank).isEqualTo(new OrderCancelService.CancelResolution("CANCELING", "UNKNOWN"));
        }

        assertNothingChanged();
    }

    @Test
    void 성공_확인_결제사_상태_문자열은_영문_숫자_밑줄만_남기고_40자까지만_응답과_로그에_나온다() {
        String longStatus = "A".repeat(200);
        String exactly40 = "B".repeat(40);
        String of41 = "C".repeat(41);
        String[][] cases = {
                {"PARTIAL_CANCELLED", "PARTIAL_CANCELLED"},
                {"PAID\nforged=line\r\u0000\u001b[31m", "PAID?forged?line????31m"},
                {"취소됨", "???"},
                {"a b-c.d", "a?b?c?d"},
                {longStatus, "A".repeat(40)},
                {exactly40, exactly40},
                {of41, "C".repeat(40)}};
        for (String[] c : cases) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(c[0]));

            OrderCancelService.CancelResolution result = service().checkCancelByAdmin(ORDER, ACTOR);

            assertThat(result.providerStatus()).as(c[1]).isEqualTo(c[1]);
            assertThat(result.status()).isEqualTo("CANCELING");
        }

        assertThat(logs.list).hasSize(cases.length).allSatisfy(e -> {
            assertThat(e.getFormattedMessage()).doesNotContain("\n").doesNotContain("\r").doesNotContain("\u001b")
                    .doesNotContain("\u0000").doesNotContain("취소됨").doesNotContain("A".repeat(41))
                    .doesNotContain("C".repeat(41));
        });
        assertNothingChanged();
    }

    @Test
    void 성공_확인_결제사_상태는_걸러내기_전_원래_값으로_판단하므로_뒤에_붙은_문자가_있는_취소됨은_마무리하지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED\n"));

        assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                .isEqualTo(new OrderCancelService.CancelResolution("CANCELING", "CANCELLED?"));

        assertNothingChanged();
    }

    @Test
    void 실패_확인_취소됨이지만_다른_상점_결제면_마무리하지_않고_취소중_그대로_취소됨을_알린다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(new PaymentGateway.Payment(ORDER, "other-store", "CANCELLED", 13_500L, "KRW", "tx-1", "LIVE"));

        assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                .isEqualTo(new OrderCancelService.CancelResolution("CANCELING", "CANCELLED"));

        assertNothingChanged();
    }

    @Test
    void 실패_확인_취소됨이지만_결제_id가_주문_id와_다르면_마무리하지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(new PaymentGateway.Payment("another-order", STORE, "CANCELLED", 13_500L, "KRW", "tx-1", "LIVE"));

        assertThat(service().checkCancelByAdmin(ORDER, ACTOR))
                .isEqualTo(new OrderCancelService.CancelResolution("CANCELING", "CANCELLED"));

        assertNothingChanged();
    }

    @Test
    void 실패_확인_상점_id_설정이_비어_있으면_결제사를_부르기_전에_설정_없음_503이다() {
        org.mockito.Mockito.lenient().when(gateway.isConfigured()).thenReturn(true); // the blank store id alone must refuse
        for (String blankStore : new String[]{"", "   ", null}) {
            assertThatThrownBy(() -> service(blankStore).checkCancelByAdmin(ORDER, ACTOR))
                    .as("[%s]", blankStore).isInstanceOf(PaymentNotConfiguredException.class);
        }

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_확인_결제사_조회가_실패하면_503_예외가_그대로_올라가고_아무것도_바꾸지_않으며_로그도_없다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        when(gateway.find(ORDER)).thenThrow(new PaymentProviderUnavailableException());

        assertThatThrownBy(() -> service().checkCancelByAdmin(ORDER, ACTOR))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_확인_마무리하는_사이_주문이_사라졌으면_404가_그대로_올라간다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenThrow(new OrderNotFoundException());

        assertThatThrownBy(() -> service().checkCancelByAdmin(ORDER, ACTOR)).isInstanceOf(OrderNotFoundException.class);

        assertThat(logs.list).isEmpty();
    }

    // ------------------------------------------------------------------ abandon: guards

    @Test
    void 실패_포기_결제_설정이_없으면_설정_없음이고_아무것도_건드리지_않는다() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR))
                .isInstanceOf(PaymentNotConfiguredException.class);

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_없는_주문은_404이고_결제사를_부르지_않는다() {
        when(gateway.isConfigured()).thenReturn(true);
        when(orderRepository.findById(ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).isInstanceOf(OrderNotFoundException.class);

        verify(gateway, never()).find(anyString());
        assertNothingChanged();
    }

    @Test
    void 실패_포기_취소중이_아닌_모든_상태는_409_INVALID_ORDER_STATE이고_이미_취소됨_환불됨도_마찬가지다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_PAID, Order.STATUS_EXPIRED,
                Order.STATUS_PAYMENT_REVIEW, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED, Order.STATUS_CANCELLED,
                Order.STATUS_REFUNDED)) {
            when(gateway.isConfigured()).thenReturn(true);
            when(orderRepository.findById(ORDER)).thenReturn(Optional.of(order(status)));

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as(status)
                    .isInstanceOfSatisfying(InvalidOrderStateException.class,
                            e -> assertThat(e.getCode()).isEqualTo("INVALID_ORDER_STATE"));
        }

        verify(gateway, never()).find(anyString());
        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    // ------------------------------------------------------------------ abandon: provider answers

    @Test
    void 성공_포기_결제사가_결제완료면_되돌리기를_한_번만_부르고_되돌아간_상태를_돌려준다() {
        for (String restored : List.of(Order.STATUS_PAID, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED)) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment("PAID"));
            when(transactions.revertCancel(ORDER)).thenReturn(restored);

            assertThat(service().abandonCancelByAdmin(ORDER, ACTOR))
                    .as(restored).isEqualTo(new OrderCancelService.CancelResolution(restored, "PAID"));
        }

        verify(transactions, org.mockito.Mockito.times(3)).revertCancel(ORDER);
        verify(transactions, never()).applyCancelled(anyString());
    }

    @Test
    void 실패_포기_결제사에_그런_결제가_없으면_되돌리지_않고_결제_정보_없음_409_CANCEL_PROVIDER_STATE다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(null);

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR))
                .isInstanceOfSatisfying(CancelProviderStateException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CANCEL_PROVIDER_STATE");
                    assertThat(e.getMessage()).isEqualTo("결제사에 이 주문의 결제 정보가 없어요. PortOne 콘솔에서 확인해 주세요.");
                });

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_결제완료여도_결제_id가_주문과_다르거나_상점이_다르면_되돌리지_않고_거절한다() {
        PaymentGateway.Payment[] wrong = {
                new PaymentGateway.Payment("another-order", STORE, "PAID", 13_500L, "KRW", "tx-1", "LIVE"),
                new PaymentGateway.Payment(ORDER, "other-store", "PAID", 13_500L, "KRW", "tx-1", "LIVE"),
                new PaymentGateway.Payment(null, STORE, "PAID", 13_500L, "KRW", "tx-1", "LIVE"),
                new PaymentGateway.Payment(ORDER, null, "PAID", 13_500L, "KRW", "tx-1", "LIVE"),
                new PaymentGateway.Payment(ORDER, "", "PAID", 13_500L, "KRW", "tx-1", "LIVE")};
        for (PaymentGateway.Payment p : wrong) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(p);

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as(p.toString())
                    .isInstanceOfSatisfying(CancelProviderStateException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("CANCEL_PROVIDER_STATE");
                        assertThat(e.getMessage()).contains("PAID");
                    });
        }

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_상점_id_설정이_비어_있으면_결제사를_부르기_전에_설정_없음_503이다() {
        org.mockito.Mockito.lenient().when(gateway.isConfigured()).thenReturn(true);
        for (String blankStore : new String[]{"", "   ", null}) {
            assertThatThrownBy(() -> service(blankStore).abandonCancelByAdmin(ORDER, ACTOR))
                    .as("[%s]", blankStore).isInstanceOf(PaymentNotConfiguredException.class);
        }

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_포기_결제사_조회는_트랜잭션_호출보다_앞서고_되돌리기는_조회_뒤에_한다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));
        when(transactions.revertCancel(ORDER)).thenReturn(Order.STATUS_PAID);

        service().abandonCancelByAdmin(ORDER, ACTOR);

        InOrder inOrder = inOrder(orderRepository, gateway, transactions);
        inOrder.verify(orderRepository).findById(ORDER);
        inOrder.verify(gateway).find(ORDER);
        inOrder.verify(transactions).revertCancel(ORDER);
    }

    @Test
    void 실패_포기_결제사가_이미_취소했으면_409_CANCEL_ALREADY_DONE이고_되돌리지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR))
                .isInstanceOfSatisfying(CancelAlreadyDoneException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CANCEL_ALREADY_DONE"));

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_취소됨이면_상점_id가_달라도_안전한_쪽으로_거절한다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(new PaymentGateway.Payment(ORDER, "other-store", "CANCELLED", 13_500L, "KRW", "tx-1", "LIVE"));

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).isInstanceOf(CancelAlreadyDoneException.class);

        assertNothingChanged();
    }

    @Test
    void 실패_포기_결제완료_취소됨이_아닌_그_밖의_상태는_409_CANCEL_PROVIDER_STATE이고_메시지에_상태가_들어_있다() {
        for (String providerStatus : List.of("PARTIAL_CANCELLED", "READY", "FAILED", "PAY_PENDING", "SOMETHING_NEW",
                "paid", "Paid")) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(providerStatus));

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as("[%s]", providerStatus)
                    .isInstanceOfSatisfying(CancelProviderStateException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("CANCEL_PROVIDER_STATE");
                        assertThat(e.getMessage()).contains(providerStatus);
                    });
        }

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_결제는_있는데_상태가_null이거나_비었으면_UNKNOWN으로_거절하고_되돌리지_않는다() {
        for (String blank : new String[]{null, "", "  "}) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(blank));

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as("[%s]", blank)
                    .isInstanceOfSatisfying(CancelProviderStateException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("CANCEL_PROVIDER_STATE");
                        assertThat(e.getMessage()).contains("UNKNOWN").doesNotContain("NOT_FOUND");
                    });
        }

        assertNothingChanged();
    }

    @Test
    void 실패_포기_결제사_상태는_원래_값으로_판단하고_메시지에는_걸러서_넣는다() {
        String[][] cases = {
                {"PAID\n", "PAID?"},                     // not exactly PAID: refused, newline not echoed
                {"PAID ", "PAID?"},
                {"CANCELLED\n", "CANCELLED?"},            // raw compare: not CANCELLED, so 409 provider state, not already-done
                {"취소 완료", "?????"},
                {"X".repeat(200), "X".repeat(40)}};
        for (String[] c : cases) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment(c[0]));

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as(c[1])
                    .isInstanceOfSatisfying(CancelProviderStateException.class, e -> {
                        assertThat(e.getMessage()).contains("상태가 " + c[1] + "이라").doesNotContain("\n")
                                .doesNotContain("X".repeat(41)).doesNotContain("완료");
                    });
        }

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_포기_결제사_상태가_PAID이면_응답에는_PAID가_그대로_나온다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));
        when(transactions.revertCancel(ORDER)).thenReturn(Order.STATUS_PAID);

        assertThat(service().abandonCancelByAdmin(ORDER, ACTOR).providerStatus()).isEqualTo("PAID");
    }

    // ------------------------------------------------------------------ PaymentService guards used by the seller actions

    @Test
    void 성공_결제사가_PAID이고_주문_id와_상점_id가_같을_때만_PAID로_인정한다() {
        PaymentService payments = new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), STORE, false);

        assertThat(payments.isPaidAtProvider(ORDER, payment("PAID"))).isTrue();
        assertThat(payments.isPaidAtProvider(ORDER, payment("CANCELLED"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER, payment("PARTIAL_CANCELLED"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER, payment("paid"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER, payment("PAID\n"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER, payment(null))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER, null)).isFalse();
        assertThat(payments.isPaidAtProvider("another-order", payment("PAID"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER,
                new PaymentGateway.Payment(ORDER, "other-store", "PAID", 1L, "KRW", "t", "LIVE"))).isFalse();
        assertThat(payments.isPaidAtProvider(ORDER,
                new PaymentGateway.Payment(ORDER, null, "PAID", 1L, "KRW", "t", "LIVE"))).isFalse();
    }

    @Test
    void 실패_상점_id_설정이_비어_있으면_양쪽_상점_id가_비어_있어도_PAID나_CANCELLED로_인정하지_않는다() {
        for (String blankStore : new String[]{"", "  ", null}) {
            PaymentService payments = new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), blankStore, false);

            assertThat(payments.isPaidAtProvider(ORDER,
                    new PaymentGateway.Payment(ORDER, blankStore, "PAID", 1L, "KRW", "t", "LIVE"))).as("paid [%s]", blankStore).isFalse();
            assertThat(payments.isCancelledAtProvider(ORDER,
                    new PaymentGateway.Payment(ORDER, blankStore, "CANCELLED", 1L, "KRW", "t", "LIVE"))).as("cancelled [%s]", blankStore).isFalse();
        }
    }

    @Test
    void 성공_결제_설정은_상점_id와_API_비밀키가_모두_있을_때만_켜진_것이다() {
        when(gateway.isConfigured()).thenReturn(true);
        PaymentService configured = new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), STORE, false);

        assertThat(configured.isConfigured()).isTrue();
        for (String blankStore : new String[]{"", "  ", null}) {
            PaymentService noStore = new PaymentService(orderRepository, gateway, transactions, verifier, new ObjectMapper(),
                    Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC), blankStore, false);
            assertThat(noStore.isConfigured()).as("[%s]", blankStore).isFalse();
        }
        when(gateway.isConfigured()).thenReturn(false);
        assertThat(configured.isConfigured()).as("API secret missing").isFalse();
    }

    @Test
    void 실패_포기_결제사_조회가_실패하면_503_예외가_그대로_올라가고_아무것도_바꾸지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        when(gateway.find(ORDER)).thenThrow(new PaymentProviderUnavailableException());

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        assertNothingChanged();
        assertThat(logs.list).isEmpty();
    }

    // ------------------------------------------------------------------ abandon: race between lookup and revert

    @Test
    void 실패_포기_되돌리는_사이_다른_경로가_이미_취소됨으로_마무리했으면_CANCEL_ALREADY_DONE이다() {
        for (String done : List.of(Order.STATUS_CANCELLED, Order.STATUS_REFUNDED)) {
            configuredWithOrder(Order.STATUS_CANCELING);
            providerSays(payment("PAID"));
            when(transactions.revertCancel(ORDER)).thenReturn(done);

            assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).as(done)
                    .isInstanceOf(CancelAlreadyDoneException.class);
        }

        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_되돌리는_사이_주문이_사라졌으면_404이다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));
        when(transactions.revertCancel(ORDER)).thenReturn(null);

        assertThatThrownBy(() -> service().abandonCancelByAdmin(ORDER, ACTOR)).isInstanceOf(OrderNotFoundException.class);

        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_포기_되돌리는_사이_다른_관리자가_이미_되돌렸으면_현재_상태를_그대로_돌려준다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));
        when(transactions.revertCancel(ORDER)).thenReturn(Order.STATUS_SHIPPED);

        assertThat(service().abandonCancelByAdmin(ORDER, ACTOR).status()).isEqualTo("SHIPPED");
    }

    // ------------------------------------------------------------------ log line

    @Test
    void 성공_확인_로그는_주문_동작_결제사_상태_결과_상태_수정한_사람만_INFO_한_줄로_남긴다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("CANCELLED"));
        when(transactions.applyCancelled(ORDER)).thenReturn(applied(Order.STATUS_CANCELLED).withStock());

        service().checkCancelByAdmin(ORDER, "actor-A");

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("orderId=" + ORDER).contains("action=check")
                    .contains("providerStatus=CANCELLED").contains("status=CANCELLED").contains("actor=actor-A")
                    .doesNotContain("홍길동").doesNotContain("010-1234-5678").doesNotContain("서울시");
        });
    }

    @Test
    void 성공_확인_로그는_수정한_사람을_호출한_사람으로_남기고_결제사가_취소하지_않았으면_취소중으로_남긴다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));

        service().checkCancelByAdmin(ORDER, "actor-B");

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("actor=actor-B").doesNotContain("actor-A")
                    .contains("providerStatus=PAID").contains("status=CANCELING").contains("action=check");
        });
    }

    @Test
    void 성공_이미_끝난_주문의_확인도_로그를_한_줄_남기고_결제사를_묻지_않았으니_결제사_상태는_비워_둔다() {
        configuredWithOrder(Order.STATUS_REFUNDED);

        assertThat(service().checkCancelByAdmin(ORDER, "actor-C"))
                .isEqualTo(new OrderCancelService.CancelResolution("REFUNDED", null));

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("action=check").contains("status=REFUNDED")
                    .contains("providerStatus=null").contains("actor=actor-C").contains(ORDER);
        });
        verify(gateway, never()).find(anyString());
    }

    @Test
    void 성공_포기_로그는_되돌아간_상태와_수정한_사람을_남기고_개인정보는_남기지_않는다() {
        configuredWithOrder(Order.STATUS_CANCELING);
        providerSays(payment("PAID"));
        when(transactions.revertCancel(ORDER)).thenReturn(Order.STATUS_DELIVERED);

        service().abandonCancelByAdmin(ORDER, "actor-D");

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("orderId=" + ORDER).contains("action=abandon")
                    .contains("providerStatus=PAID").contains("status=DELIVERED").contains("actor=actor-D")
                    .doesNotContain("홍길동").doesNotContain("010-1234-5678").doesNotContain("서울시");
        });
    }
}
