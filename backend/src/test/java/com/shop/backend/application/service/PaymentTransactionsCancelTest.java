package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Plain Mockito: the sequence of calls of the cancel transactions. Row locks and real SQL are the integration tests' job. */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionsCancelTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String OWNER = "u";

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductOptionRepository productOptionRepository;
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private PaymentTransactions tx() {
        return new PaymentTransactions(orderRepository, productRepository, productOptionRepository, em, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Lines are added in descending product id order on purpose. */
    private static Order order(String status, String... productIds) {
        Order order = new Order(OWNER, "k", "h", "구매자", "010", "서울", 1000, 0, NOW);
        ReflectionTestUtils.setField(order, "status", status);
        for (String productId : productIds) {
            Product product = new Product("상품", "설명", 1000, "http://img", 10);
            ReflectionTestUtils.setField(product, "id", productId);
            order.addItem(new OrderItem(product, 2, 1000));
        }
        return order;
    }

    // beginCancel

    @Test
    void 성공_고객_취소_시작은_행을_잠근_뒤_결제완료를_취소중으로_바꾸고_재고는_건드리지_않는다() {
        Order order = order(Order.STATUS_PAID, "pB", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        PaymentTransactions.CancelStart start = tx().beginCancel("o1", OWNER, "사유");

        assertThat(start).isEqualTo(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        InOrder inOrder = inOrder(em, orderRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(orderRepository).findByIdForUpdate("o1");
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_이미_취소된_주문은_잠근_뒤_할_일_없음으로_돌려준다() {
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order(Order.STATUS_CANCELLED)));

        assertThat(tx().beginCancel("o1", OWNER, null))
                .isEqualTo(new PaymentTransactions.CancelStart(Order.STATUS_CANCELLED, false));
    }

    @Test
    void 실패_남의_주문과_없는_주문과_빈_사용자는_404다() {
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order(Order.STATUS_PAID)));
        when(orderRepository.findByIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tx().beginCancel("o1", "someone-else", null)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> tx().beginCancel("o1", " ", null)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> tx().beginCancel("missing", OWNER, null)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void 실패_잠근_뒤_이미_배송중이면_고객_취소는_거부되고_상태는_그대로다() {
        Order order = order(Order.STATUS_SHIPPED);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> tx().beginCancel("o1", OWNER, null)).isInstanceOf(OrderNotCancellableException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_SHIPPED);
    }

    @Test
    void 성공_판매자_환불_시작은_소유자_확인_없이_배송중을_취소중으로_바꾼다() {
        Order order = order(Order.STATUS_DELIVERED);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().beginCancel("o1", null, "하자").callProvider()).isTrue();
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_DELIVERED);
    }

    @Test
    void 성공_결제완료_주문도_판매자가_취소를_시작할_수_있고_직전_상태와_사유가_남는다() {
        Order order = order(Order.STATUS_PAID);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().beginCancel("o1", null, "품절")).isEqualTo(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        assertThat(order.getCancelReason()).isEqualTo("품절");
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_이미_취소된_주문의_판매자_취소는_할_일_없음이고_결제전_상태는_거부한다() {
        when(orderRepository.findByIdForUpdate("done")).thenReturn(Optional.of(order(Order.STATUS_CANCELLED)));
        when(orderRepository.findByIdForUpdate("pending")).thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT)));

        assertThat(tx().beginCancel("done", null, "x")).isEqualTo(new PaymentTransactions.CancelStart(Order.STATUS_CANCELLED, false));
        assertThatThrownBy(() -> tx().beginCancel("pending", null, "x")).isInstanceOf(InvalidOrderStateException.class);
    }

    // revertCancel

    @Test
    void 성공_되돌리기는_잠근_뒤_취소중을_직전_상태로_돌린다() {
        Order order = order(Order.STATUS_PAID);
        order.beginCustomerCancel(null);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        tx().revertCancel("o1");

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 성공_사라진_주문이거나_이미_취소중이_아니면_되돌리기는_아무것도_하지_않는다() {
        Order done = order(Order.STATUS_CANCELLED);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(done));
        when(orderRepository.findByIdForUpdate("gone")).thenReturn(Optional.empty());

        tx().revertCancel("o1");
        tx().revertCancel("gone");

        assertThat(done.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    // applyCancelled

    @Test
    void 성공_결제완료_취소는_취소됨으로_바꾸고_재고를_상품_id_순으로_돌려준다() {
        Order order = order(Order.STATUS_PAID, "pB", "pA");
        order.beginCustomerCancel(null);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        PaymentTransactions.CancelApplied applied = tx().applyCancelled("o1");

        assertThat(applied).isEqualTo(new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED,
                Order.CancelOutcome.RESTORE_STOCK));
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        InOrder inOrder = inOrder(em, orderRepository, productRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(orderRepository).findByIdForUpdate("o1");
        inOrder.verify(productRepository).incrementStock("pA", 2);
        inOrder.verify(productRepository).incrementStock("pB", 2);
    }

    @Test
    void 성공_배송_후_환불은_환불됨으로_바꾸고_재고는_돌려주지_않는다() {
        Order order = order(Order.STATUS_SHIPPED, "pA");
        order.beginRefund("x");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyCancelled("o1").status()).isEqualTo(Order.STATUS_REFUNDED);

        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_이미_취소된_주문에_다시_적용해도_재고를_두_번_돌려주지_않는다() {
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order(Order.STATUS_CANCELLED, "pA")));

        assertThat(tx().applyCancelled("o1").outcome()).isEqualTo(Order.CancelOutcome.ALREADY_FINAL);

        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_결제_검토_주문은_취소됨이_되지만_재고는_돌려주지_않는다() {
        Order order = order(Order.STATUS_PAYMENT_REVIEW, "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyCancelled("o1").status()).isEqualTo(Order.STATUS_CANCELLED);

        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_판매자_환불은_멈춘_결제완료발_취소중_주문을_이어받고_사유는_고객이_남긴_것을_유지한다() {
        Order stuck = order(Order.STATUS_PAID, "pA");
        stuck.beginCustomerCancel("고객");
        when(orderRepository.findByIdForUpdate("stuck")).thenReturn(Optional.of(stuck));

        assertThat(tx().beginCancel("stuck", null, "판매자")).isEqualTo(
                new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        assertThat(stuck.getCancelReason()).isEqualTo("고객");
    }

    // applyPaid must never move an order that has already moved past PAID

    @Test
    void 성공_결제_이후_상태의_주문은_applyPaid가_아무것도_바꾸지_않고_현재_상태를_돌려준다() {
        for (String status : List.of(Order.STATUS_CANCELING, Order.STATUS_CANCELLED, Order.STATUS_SHIPPED,
                Order.STATUS_DELIVERED, Order.STATUS_REFUNDED)) {
            Order order = order(status, "pA");
            if (Order.STATUS_CANCELING.equals(status)) {
                ReflectionTestUtils.setField(order, "cancelFrom", Order.STATUS_PAID);
            }
            when(orderRepository.findByIdForUpdate("o-" + status)).thenReturn(Optional.of(order));

            assertThat(tx().applyPaid("o-" + status, "tx-late")).as(status).isEqualTo(status);

            assertThat(order.getStatus()).isEqualTo(status);
            assertThat(order.getPaidAt()).isNull();
            assertThat(order.getPaymentTransactionId()).isNull();
        }
        verifyNoInteractions(productRepository);
    }

    @Test
    void 성공_applyPaid는_PAID는_그대로_두고_PAYMENT_REVIEW는_기존대로_예외다() {
        when(orderRepository.findByIdForUpdate("paid")).thenReturn(Optional.of(order(Order.STATUS_PAID)));
        when(orderRepository.findByIdForUpdate("review")).thenReturn(Optional.of(order(Order.STATUS_PAYMENT_REVIEW)));

        assertThat(tx().applyPaid("paid", "tx")).isEqualTo(Order.STATUS_PAID);
        assertThatThrownBy(() -> tx().applyPaid("review", "tx")).isInstanceOf(PaymentNeedsReviewException.class);
    }

    @Test
    void 실패_없는_주문에_적용하면_404다() {
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tx().applyCancelled("o1")).isInstanceOf(OrderNotFoundException.class);
    }
}
