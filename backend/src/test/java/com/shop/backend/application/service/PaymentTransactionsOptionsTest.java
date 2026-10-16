package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stock restore / re-reservation for order lines with options (V18): which table each line hits and in which order.
 * The lines are added in reverse key order on purpose; a legacy line is "id", an option line is "id/option".
 */
@ExtendWith(MockitoExtension.class)
class PaymentTransactionsOptionsTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductOptionRepository productOptionRepository;
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private PaymentTransactions tx() {
        return new PaymentTransactions(orderRepository, productRepository, productOptionRepository, em,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order(String status, Instant created, String... lines) {
        Order order = new Order("u", "k", "h", "구매자", "010", "서울", 1000, 0, created);
        ReflectionTestUtils.setField(order, "status", status);
        for (String line : lines) {
            String[] parts = line.split("/");
            Product product = new Product("상품", "설명", 1000, "http://img", 10);
            ReflectionTestUtils.setField(product, "id", parts[0]);
            order.addItem(parts.length == 1 ? new OrderItem(product, 2, 1000)
                    : new OrderItem(product, 2, 1000, parts[1], "라벨 " + parts[1]));
        }
        return order;
    }

    // ---- expiry ----

    @Test
    void 성공_만료는_옵션_줄은_옵션_재고로_옵션_없는_줄은_상품_재고로_키_순서대로_돌려준다() {
        Order order = order(Order.STATUS_PENDING_PAYMENT, NOW.minusSeconds(3600), "pC", "pB/o2", "pB/o1", "pA");
        when(orderRepository.findByIdForUpdateSkipLocked("o1")).thenReturn(Optional.of(order));

        assertThat(tx().expireOne("o1")).isTrue();

        InOrder inOrder = inOrder(productRepository, productOptionRepository);
        inOrder.verify(productRepository).incrementStock("pA", 2);
        inOrder.verify(productOptionRepository).incrementStock("o1", 2);
        inOrder.verify(productOptionRepository).incrementStock("o2", 2);
        inOrder.verify(productRepository).incrementStock("pC", 2);
        verify(productRepository, never()).incrementStock("pB", 2);
    }

    @Test
    void 실패_만료되지_않았으면_옵션_재고도_돌려주지_않는다() {
        Order fresh = order(Order.STATUS_PENDING_PAYMENT, NOW, "pA/o1");
        when(orderRepository.findByIdForUpdateSkipLocked("o1")).thenReturn(Optional.of(fresh));

        assertThat(tx().expireOne("o1")).isFalse();

        verify(productOptionRepository, never()).incrementStock(anyString(), anyInt());
    }

    // ---- cancel / refund ----

    @Test
    void 성공_결제완료_취소는_옵션_재고를_옵션에_돌려준다() {
        Order order = order(Order.STATUS_PAID, NOW, "pB/o1", "pA");
        order.beginCustomerCancel(null);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyCancelled("o1").outcome()).isEqualTo(Order.CancelOutcome.RESTORE_STOCK);

        InOrder inOrder = inOrder(productRepository, productOptionRepository);
        inOrder.verify(productRepository).incrementStock("pA", 2);
        inOrder.verify(productOptionRepository).incrementStock("o1", 2);
        verify(productRepository, never()).incrementStock("pB", 2);
    }

    @Test
    void 성공_배송_후_환불과_이미_취소된_주문은_옵션_재고도_돌려주지_않는다() {
        Order shipped = order(Order.STATUS_SHIPPED, NOW, "pA/o1");
        shipped.beginRefund("x");
        when(orderRepository.findByIdForUpdate("shipped")).thenReturn(Optional.of(shipped));
        when(orderRepository.findByIdForUpdate("done")).thenReturn(Optional.of(order(Order.STATUS_CANCELLED, NOW, "pA/o1")));

        tx().applyCancelled("shipped");
        tx().applyCancelled("done");

        verify(productOptionRepository, never()).incrementStock(anyString(), anyInt());
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    // ---- late payment re-reservation ----

    @Test
    void 성공_만료된_주문의_늦은_결제는_옵션_줄은_옵션_재고에서_키_순서로_다시_잡는다() {
        Order order = order(Order.STATUS_EXPIRED, NOW, "pC", "pB/o2", "pB/o1", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(productRepository.decrementStockIfAvailable(anyString(), anyInt())).thenReturn(1);
        when(productOptionRepository.decrementStockIfAvailable(anyString(), anyString(), anyInt())).thenReturn(1);

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAID);

        InOrder inOrder = inOrder(productRepository, productOptionRepository);
        inOrder.verify(productRepository).decrementStockIfAvailable("pA", 2);
        inOrder.verify(productOptionRepository).decrementStockIfAvailable("o1", "pB", 2);
        inOrder.verify(productOptionRepository).decrementStockIfAvailable("o2", "pB", 2);
        inOrder.verify(productRepository).decrementStockIfAvailable("pC", 2);
    }

    @Test
    void 실패_옵션_재고가_모자라면_잡은_것을_각자의_표에_되돌리고_결제확인필요로_바꾼다() {
        // key order: pA (legacy), pB/o1 (option, reserved), pB/o2 (option, short), pC (never tried)
        Order order = order(Order.STATUS_EXPIRED, NOW, "pC", "pB/o2", "pB/o1", "pA");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(productRepository.decrementStockIfAvailable("pA", 2)).thenReturn(1);
        when(productOptionRepository.decrementStockIfAvailable("o1", "pB", 2)).thenReturn(1);
        when(productOptionRepository.decrementStockIfAvailable("o2", "pB", 2)).thenReturn(0);

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAYMENT_REVIEW);

        verify(productRepository).incrementStock("pA", 2);
        verify(productOptionRepository).incrementStock("o1", 2);
        verify(productOptionRepository, never()).incrementStock("o2", 2);
        verify(productRepository, never()).decrementStockIfAvailable("pC", 2);
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    void 실패_첫_옵션부터_모자라면_되돌릴_것이_없다() {
        Order order = order(Order.STATUS_EXPIRED, NOW, "pA/o1");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));
        when(productOptionRepository.decrementStockIfAvailable("o1", "pA", 2)).thenReturn(0);

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAYMENT_REVIEW);

        verify(productOptionRepository, never()).incrementStock(anyString(), anyInt());
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }

    @Test
    void 성공_결제_대기_주문의_결제는_옵션_재고를_건드리지_않는다() {
        Order order = order(Order.STATUS_PENDING_PAYMENT, NOW, "pA/o1");
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(tx().applyPaid("o1", "tx-1")).isEqualTo(Order.STATUS_PAID);

        verify(productOptionRepository, never()).decrementStockIfAvailable(anyString(), anyString(), anyInt());
    }

    // ---- shared helper used by the order service's replay path ----

    @Test
    void 성공_restoreStock는_주문_항목_순서와_상관없이_키_순서로_돌려준다() {
        Order order = order(Order.STATUS_EXPIRED, NOW, "pB/o9", "pB/o1", "pA/o5");

        PaymentTransactions.restoreStock(productRepository, productOptionRepository, order);

        InOrder inOrder = inOrder(productOptionRepository);
        inOrder.verify(productOptionRepository).incrementStock("o5", 2);
        inOrder.verify(productOptionRepository).incrementStock("o1", 2);
        inOrder.verify(productOptionRepository).incrementStock("o9", 2);
        verify(productRepository, never()).incrementStock(anyString(), anyInt());
    }
}
