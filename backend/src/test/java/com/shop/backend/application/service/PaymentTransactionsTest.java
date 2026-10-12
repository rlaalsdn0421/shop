package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.infrastructure.repository.OrderRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Plain Mockito: the sequence of calls. Row locks and real SQL are the integration tests' job. */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionsTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private PaymentTransactions tx() {
        return new PaymentTransactions(orderRepository, productRepository, em, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Lines are added in descending product id order on purpose. */
    private static Order order(String status, Instant created, String... productIds) {
        Order order = new Order("u", "k", "h", "구매자", "010", "서울", 1000, 0, created);
        ReflectionTestUtils.setField(order, "status", status);
        for (String productId : productIds) {
            Product product = new Product("상품", "설명", 1000, "http://img", 10);
            ReflectionTestUtils.setField(product, "id", productId);
            order.addItem(new OrderItem(product, 2, 1000));
        }
        return order;
    }

    @Test
    void 성공_결제_대기_주문은_잠근_뒤_결제완료로_바꾸고_재고는_건드리지_않는다() {
        Order order = order(Order.STATUS_PENDING_PAYMENT, NOW, "pB", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAID);

        InOrder inOrder = inOrder(em, orderRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(orderRepository).findByIdForUpdate("o1");
        verify(productRepository, never()).decrementStockIfAvailable(anyString(), anyInt());
        assertThat(order.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void 성공_이미_결제된_주문은_그대로_결제완료다() {
        Order order = order(Order.STATUS_PAID, NOW, "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyPaid("o1", "tx-2")).isEqualTo(Order.STATUS_PAID);

        assertThat(order.getPaymentTransactionId()).isNull(); // untouched
    }

    @Test
    void 성공_만료된_주문은_재고를_상품_id_순으로_다시_잡고_결제완료로_바꾼다() {
        Order order = order(Order.STATUS_EXPIRED, NOW, "pB", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(productRepository.decrementStockIfAvailable(anyString(), anyInt())).thenReturn(1);

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAID);

        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).decrementStockIfAvailable("pA", 2);
        inOrder.verify(productRepository).decrementStockIfAvailable("pB", 2);
    }

    @Test
    void 실패_만료된_주문의_재고가_모자라면_잡은_것을_되돌리고_결제확인필요로_바꾼다() {
        Order order = order(Order.STATUS_EXPIRED, NOW, "pC", "pB", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(productRepository.decrementStockIfAvailable("pA", 2)).thenReturn(1);
        when(productRepository.decrementStockIfAvailable("pB", 2)).thenReturn(0);

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAYMENT_REVIEW);

        verify(productRepository).incrementStock("pA", 2);
        verify(productRepository, never()).incrementStock("pB", 2);
        verify(productRepository, never()).decrementStockIfAvailable("pC", 2);
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    void 실패_없는_주문은_찾을_수_없다() {
        when(orderRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tx().applyPaid("nope", "tx")).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void 성공_만료된_대기_주문은_재고를_상품_id_순으로_되돌린다() {
        Order order = order(Order.STATUS_PENDING_PAYMENT, NOW.minusSeconds(3600), "pB", "pA");
        when(orderRepository.findByIdForUpdateSkipLocked("o1")).thenReturn(Optional.of(order));

        assertThat(tx().expireOne("o1")).isTrue();

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_EXPIRED);
        InOrder inOrder = inOrder(productRepository);
        inOrder.verify(productRepository).incrementStock("pA", 2);
        inOrder.verify(productRepository).incrementStock("pB", 2);
    }

    @Test
    void 실패_잠금을_못_잡았거나_그새_결제됐거나_아직_기한_전이면_건드리지_않는다() {
        when(orderRepository.findByIdForUpdateSkipLocked("locked")).thenReturn(Optional.empty());
        when(orderRepository.findByIdForUpdateSkipLocked("paid"))
                .thenReturn(Optional.of(order(Order.STATUS_PAID, NOW.minusSeconds(3600), "pA")));
        when(orderRepository.findByIdForUpdateSkipLocked("fresh"))
                .thenReturn(Optional.of(order(Order.STATUS_PENDING_PAYMENT, NOW, "pA")));

        assertThat(tx().expireOne("locked")).isFalse();
        assertThat(tx().expireOne("paid")).isFalse();
        assertThat(tx().expireOne("fresh")).isFalse();

        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }
}
