package com.shop.backend.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduler's stuck-cancel cleanup ({@link OrderCancelService#resolveStuckCancels()}, docs/cancel-resolve-design.md).
 * The PaymentService is the REAL one (only its collaborators are mocks) so the store/id verification that decides
 * "cancelled at the provider" is exercised, not stubbed away.
 */
@ExtendWith(MockitoExtension.class)
class OrderCancelStuckResolveTest {

    private static final String STORE = "store-test";

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

    private static Order order(String id, String status) {
        Order order = new Order("user-1", "k", "h", "홍길동", "010-1234-5678", "서울시 어딘가 1", 13_500, 3_500);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private static PaymentGateway.Payment payment(String id, String store, String status) {
        return new PaymentGateway.Payment(id, store, status, 13_500L, "KRW", "tx-" + id, "LIVE");
    }

    private void configured() {
        when(gateway.isConfigured()).thenReturn(true);
    }

    private void candidates(String... ids) {
        when(orderRepository.findCancelingIds(any(Pageable.class))).thenReturn(List.of(ids));
    }

    /** The order is CANCELING and the provider answers {@code providerStatus} for it. */
    private void stuck(String id, String providerStatus) {
        when(orderRepository.findById(id)).thenReturn(Optional.of(order(id, Order.STATUS_CANCELING)));
        when(gateway.find(id)).thenReturn(Optional.of(payment(id, STORE, providerStatus)));
    }

    private void applyReturns(String id, String newStatus) {
        when(transactions.applyCancelled(id)).thenReturn(
                new PaymentTransactions.CancelApplied(newStatus, Order.CancelOutcome.RESTORE_STOCK));
    }

    private void assertNoCancelWritten() {
        verify(transactions, never()).applyCancelled(anyString());
        verify(transactions, never()).revertCancel(anyString());
        verify(transactions, never()).beginCancel(anyString(), any(), any());
        verify(gateway, never()).cancel(anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ guards

    @Test
    void 실패_결제_설정이_꺼져_있으면_0이고_저장소_게이트웨이_트랜잭션을_건드리지_않는다() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThat(service().resolveStuckCancels()).isZero();

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
    }

    @Test
    void 실패_상점_id_설정이_비어_있으면_0이고_저장소도_결제사도_건드리지_않는다() {
        lenient().when(gateway.isConfigured()).thenReturn(true);
        for (String blankStore : new String[]{"", "   ", null}) {
            assertThat(service(blankStore).resolveStuckCancels()).as("[%s]", blankStore).isZero();
        }

        verifyNoInteractions(orderRepository, transactions);
        verify(gateway, never()).find(anyString());
    }

    @Test
    void 성공_취소중_후보가_없으면_0이고_결제사를_묻지_않는다() {
        configured();
        candidates();

        assertThat(service().resolveStuckCancels()).isZero();

        verify(gateway, never()).find(anyString());
        assertNoCancelWritten();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 성공_저장소는_최신순_앞_5건_한_페이지만_요청한다() {
        configured();
        candidates();

        service().resolveStuckCancels();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findCancelingIds(page.capture());
        assertThat(page.getValue().getPageNumber()).isZero();
        assertThat(page.getValue().getPageSize()).isEqualTo(5).isEqualTo(OrderCancelService.CANCEL_RESOLVE_BATCH);
        assertThat(page.getValue().getSort().isSorted()).as("ordering belongs to the query, not the pageable").isFalse();
    }

    // ------------------------------------------------------------------ counting

    @Test
    void 성공_결제사가_취소됨이고_마무리가_취소됨_환불됨을_돌려주면_각각_세고_scheduler로_남긴다() {
        configured();
        candidates("o1", "o2");
        stuck("o1", "CANCELLED");
        stuck("o2", "CANCELLED");
        applyReturns("o1", Order.STATUS_CANCELLED);
        applyReturns("o2", Order.STATUS_REFUNDED);

        assertThat(service().resolveStuckCancels()).isEqualTo(2);

        verify(transactions, times(1)).applyCancelled("o1");
        verify(transactions, times(1)).applyCancelled("o2");
        verify(transactions, never()).revertCancel(anyString());
        assertThat(logs.list).hasSize(2).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("action=check").contains("actor=scheduler").doesNotContain("홍길동").doesNotContain("010-1234-5678"));
    }

    @Test
    void 실패_결제사가_취소됨이_아니면_세지_않고_마무리도_되돌리기도_부르지_않는다() {
        configured();
        candidates("o1", "o2", "o3", "o4");
        stuck("o1", "PAID");
        stuck("o2", "PARTIAL_CANCELLED");
        stuck("o3", "READY");
        // o4: the provider has no such payment (NOT_FOUND)
        when(orderRepository.findById("o4")).thenReturn(Optional.of(order("o4", Order.STATUS_CANCELING)));
        when(gateway.find("o4")).thenReturn(Optional.empty());

        assertThat(service().resolveStuckCancels()).isZero();

        verify(gateway, times(4)).find(anyString());
        assertNoCancelWritten(); // above all: a scheduler never abandons (revertCancel) a cancel on a PAID answer
    }

    @Test
    void 실패_결제사가_취소됨이어도_다른_상점이거나_결제_id가_다르면_마무리하지_않고_세지_않는다() {
        configured();
        candidates("o1", "o2");
        when(orderRepository.findById("o1")).thenReturn(Optional.of(order("o1", Order.STATUS_CANCELING)));
        when(gateway.find("o1")).thenReturn(Optional.of(payment("o1", "other-store", "CANCELLED")));
        when(orderRepository.findById("o2")).thenReturn(Optional.of(order("o2", Order.STATUS_CANCELING)));
        when(gateway.find("o2")).thenReturn(Optional.of(payment("someone-elses-order", STORE, "CANCELLED")));

        assertThat(service().resolveStuckCancels()).isZero();

        assertNoCancelWritten();
    }

    @Test
    void 실패_이미_취소됨_환불됨인_주문은_결제사에_묻지_않고_세지_않는다() {
        configured();
        candidates("o1", "o2");
        when(orderRepository.findById("o1")).thenReturn(Optional.of(order("o1", Order.STATUS_CANCELLED)));
        when(orderRepository.findById("o2")).thenReturn(Optional.of(order("o2", Order.STATUS_REFUNDED)));

        assertThat(service().resolveStuckCancels()).isZero();

        verify(gateway, never()).find(anyString());
        assertNoCancelWritten();
    }

    @Test
    void 성공_섞여_있으면_결제사가_취소한_주문만_센다() {
        configured();
        candidates("o1", "o2", "o3");
        stuck("o1", "PAID");
        stuck("o2", "CANCELLED");
        stuck("o3", "PAID");
        applyReturns("o2", Order.STATUS_CANCELLED);

        assertThat(service().resolveStuckCancels()).isEqualTo(1);

        verify(transactions).applyCancelled("o2");
        verify(transactions, never()).applyCancelled("o1");
        verify(transactions, never()).applyCancelled("o3");
        verify(transactions, never()).revertCancel(anyString());
    }

    // ------------------------------------------------------------------ failures

    @Test
    void 실패_주문이_그_사이_취소중이_아니게_되면_경고만_남기고_다음_주문을_계속한다() {
        configured();
        candidates("o1", "o2");
        when(orderRepository.findById("o1")).thenReturn(Optional.of(order("o1", Order.STATUS_PAID))); // went back to PAID
        stuck("o2", "CANCELLED");
        applyReturns("o2", Order.STATUS_CANCELLED);

        int resolved = service().resolveStuckCancels(); // must not throw

        assertThat(resolved).isEqualTo(1);
        verify(gateway, never()).find("o1");
        verify(transactions, never()).applyCancelled("o1");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("o1").contains("InvalidOrderStateException"));
    }

    @Test
    void 실패_마무리하는_사이_상태가_바뀌어_예외가_나도_그_주문만_건너뛰고_세지_않는다() {
        configured();
        candidates("o1", "o2");
        stuck("o1", "CANCELLED");
        when(transactions.applyCancelled("o1")).thenThrow(new InvalidOrderStateException("changed"));
        stuck("o2", "CANCELLED");
        applyReturns("o2", Order.STATUS_CANCELLED);

        assertThat(service().resolveStuckCancels()).isEqualTo(1);

        verify(transactions).applyCancelled("o2");
    }

    @Test
    void 실패_결제사_장애가_나면_즉시_멈추고_뒤_후보는_조회하지_않으며_그때까지_마무리한_수를_돌려준다() {
        configured();
        candidates("o1", "o2", "o3");
        stuck("o1", "CANCELLED");
        applyReturns("o1", Order.STATUS_CANCELLED);
        when(orderRepository.findById("o2")).thenReturn(Optional.of(order("o2", Order.STATUS_CANCELING)));
        when(gateway.find("o2")).thenThrow(new PaymentProviderUnavailableException());

        int resolved = service().resolveStuckCancels(); // must not throw

        assertThat(resolved).isEqualTo(1);
        verify(orderRepository, never()).findById("o3");
        verify(gateway, never()).find("o3");
        verify(transactions, never()).applyCancelled("o3");
        verify(gateway, times(2)).find(anyString());
    }

    @Test
    void 실패_첫_주문부터_결제사_장애면_0이고_나머지는_건드리지_않는다() {
        configured();
        candidates("o1", "o2");
        when(orderRepository.findById("o1")).thenReturn(Optional.of(order("o1", Order.STATUS_CANCELING)));
        when(gateway.find("o1")).thenThrow(new PaymentProviderUnavailableException());

        assertThat(service().resolveStuckCancels()).isZero();

        verify(orderRepository, never()).findById("o2");
        assertNoCancelWritten();
    }

    @Test
    void 실패_그_밖의_예외는_그_주문만_건너뛰고_계속하며_결과에_영향이_없다() {
        configured();
        candidates("o1", "o2", "o3", "o4");
        when(orderRepository.findById("o1")).thenThrow(new CannotAcquireLockException("lock timeout"));
        when(orderRepository.findById("o2")).thenThrow(new OrderNotFoundException()); // deleted meanwhile
        when(orderRepository.findById("o3")).thenReturn(Optional.of(order("o3", Order.STATUS_CANCELING)));
        when(gateway.find("o3")).thenThrow(new IllegalStateException("boom"));
        stuck("o4", "CANCELLED");
        applyReturns("o4", Order.STATUS_CANCELLED);

        assertThat(service().resolveStuckCancels()).isEqualTo(1);

        verify(transactions).applyCancelled("o4");
        verify(transactions, never()).applyCancelled("o1");
        verify(transactions, never()).applyCancelled("o3");
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(3);
    }

    @Test
    void 실패_경고_로그에는_주문_id와_예외_종류만_남고_예외_메시지의_개인정보는_남지_않는다() {
        configured();
        candidates("o1");
        when(orderRepository.findById("o1")).thenThrow(new IllegalStateException("홍길동 010-1234-5678 서울시 어딘가 1"));

        assertThat(service().resolveStuckCancels()).isZero();

        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("o1").contains("IllegalStateException")
                    .doesNotContain("홍길동").doesNotContain("010-1234-5678").doesNotContain("서울시");
            assertThat(e.getThrowableProxy()).as("no stack trace with the message").isNull();
        });
    }
}
