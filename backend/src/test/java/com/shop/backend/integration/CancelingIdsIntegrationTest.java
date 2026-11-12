package com.shop.backend.integration;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler's candidate query {@code OrderRepository.findCancelingIds} on a real Postgres: only CANCELING orders,
 * newest created first (id tiebreak is not promised), and the page size is honoured.
 */
class CancelingIdsIntegrationTest extends PostgresIntegrationTest {

    private static final Instant T = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private OrderRepository orderRepository;

    private void order(String id, String status, Instant createdAt) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Order order = new Order(null, null, null, "구매자", "010-0000-0000", "서울", 1000, 0);
            ReflectionTestUtils.setField(order, "id", id);
            ReflectionTestUtils.setField(order, "status", status);
            if (Order.STATUS_CANCELING.equals(status)) {
                ReflectionTestUtils.setField(order, "cancelFrom", Order.STATUS_PAID); // orders_canceling_has_origin (V16)
            }
            ReflectionTestUtils.setField(order, "createdAt", createdAt);
            em.persist(order);
        });
    }

    @Test
    void 성공_취소중_주문의_id만_생성이_최근인_순서로_돌려준다() {
        order("c-old", Order.STATUS_CANCELING, T);
        order("c-new", Order.STATUS_CANCELING, T.plusSeconds(120));
        order("c-mid", Order.STATUS_CANCELING, T.plusSeconds(60));

        assertThat(orderRepository.findCancelingIds(PageRequest.of(0, 10))).containsExactly("c-new", "c-mid", "c-old");
    }

    @Test
    void 실패_취소중이_아닌_상태는_더_최근이어도_돌려주지_않는다() {
        order("c1", Order.STATUS_CANCELING, T);
        for (String other : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_PAID, Order.STATUS_EXPIRED,
                Order.STATUS_PAYMENT_REVIEW, Order.STATUS_CANCELLED, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED,
                Order.STATUS_REFUNDED)) {
            order("x-" + other, other, T.plusSeconds(500));
        }

        assertThat(orderRepository.findCancelingIds(PageRequest.of(0, 10))).containsExactly("c1");
    }

    @Test
    void 성공_취소중_주문이_없으면_빈_목록이다() {
        order("p1", Order.STATUS_PAID, T);

        assertThat(orderRepository.findCancelingIds(PageRequest.of(0, 5))).isEmpty();
    }

    @Test
    void 성공_페이지_크기만큼만_돌려주고_넘치면_가장_오래된_것이_빠진다() {
        for (int i = 0; i < 7; i++) {
            order("c" + i, Order.STATUS_CANCELING, T.plusSeconds(i)); // c6 is the newest
        }

        assertThat(orderRepository.findCancelingIds(PageRequest.of(0, 5))).containsExactly("c6", "c5", "c4", "c3", "c2");
        assertThat(orderRepository.findCancelingIds(PageRequest.of(0, 1))).containsExactly("c6");
    }
}
