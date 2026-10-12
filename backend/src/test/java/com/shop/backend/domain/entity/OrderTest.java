package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.PaymentNeedsReviewException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static Order pending() {
        return new Order("u", "k", "h", "구매자", "010", "서울", 1000, 0, NOW);
    }

    private static Order withStatus(String status) {
        Order order = pending();
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private static void addItem(Order order, String itemId, String name, int price, int quantity) {
        Product product = new Product(name, "설명", price, "http://img", 10);
        OrderItem item = new OrderItem(product, quantity, price);
        ReflectionTestUtils.setField(item, "id", itemId);
        order.addItem(item);
    }

    @Test
    void 성공_새_주문은_결제_대기이고_만료는_20분_뒤다() {
        Order order = pending();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plusSeconds(1200));
    }

    @Test
    void 성공_결제_대기_주문을_결제완료로_바꾼다() {
        Order order = pending();
        order.markPaid(NOW, "tx-1");
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(order.getPaymentTransactionId()).isEqualTo("tx-1");
    }

    @Test
    void 성공_이미_결제된_주문은_다시_결제해도_그대로다() {
        Order order = pending();
        order.markPaid(NOW, "tx-1");
        order.markPaid(NOW.plusSeconds(60), "tx-2");
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(order.getPaymentTransactionId()).isEqualTo("tx-1");
    }

    @Test
    void 성공_만료된_주문도_재고를_다시_잡았다면_결제완료로_바꾼다() {
        Order order = withStatus(Order.STATUS_EXPIRED);
        order.markPaid(NOW, "tx-1");
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAID);
    }

    @Test
    void 실패_결제_확인_필요_주문은_자동으로_결제완료가_되지_않는다() {
        Order order = withStatus(Order.STATUS_PAYMENT_REVIEW);
        assertThatThrownBy(() -> order.markPaid(NOW, "tx-1")).isInstanceOf(PaymentNeedsReviewException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAYMENT_REVIEW);
    }

    @Test
    void 성공_너무_긴_거래_id는_100자로_자른다() {
        Order order = pending();
        order.markPaid(NOW, "x".repeat(150));
        assertThat(order.getPaymentTransactionId()).hasSize(100);
    }

    @Test
    void 성공_만료_시각이_지난_결제_대기_주문만_만료된다() {
        Order order = pending();
        assertThat(order.expire(NOW.plusSeconds(1200))).isFalse(); // exactly at the deadline: still payable
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);
        assertThat(order.expire(NOW.plusSeconds(1201))).isTrue();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_EXPIRED);
    }

    @Test
    void 실패_결제완료나_만료시각_없는_주문은_만료되지_않는다() {
        Order paid = pending();
        paid.markPaid(NOW, "tx");
        assertThat(paid.expire(NOW.plusSeconds(99999))).isFalse();
        assertThat(paid.getStatus()).isEqualTo(Order.STATUS_PAID);

        Order noDeadline = new Order("u", "k", "h", "구매자", "010", "서울", 1000, 0);
        assertThat(noDeadline.expire(NOW.plusSeconds(99999))).isFalse();
    }

    @Test
    void 성공_만료된_주문만_결제확인필요로_바꿀_수_있다() {
        Order expired = withStatus(Order.STATUS_EXPIRED);
        expired.markPaymentReview();
        assertThat(expired.getStatus()).isEqualTo(Order.STATUS_PAYMENT_REVIEW);

        assertThatThrownBy(() -> pending().markPaymentReview()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 성공_주문명은_대표상품과_외_N건이다() {
        Order one = pending();
        addItem(one, "i1", "사과", 1000, 1);
        assertThat(one.orderName()).isEqualTo("사과");

        Order many = pending();
        addItem(many, "i1", "사과", 1000, 1);
        addItem(many, "i2", "배", 3000, 1);
        addItem(many, "i3", "귤", 500, 2);
        assertThat(many.orderName()).isEqualTo("배 외 2건");
    }

    @Test
    void 성공_주문명은_100자를_넘으면_접미사를_남기고_자른다() {
        Order one = pending();
        addItem(one, "i1", "가".repeat(150), 1000, 1);
        assertThat(one.orderName()).hasSize(100);

        Order many = pending();
        addItem(many, "i1", "가".repeat(150), 5000, 1);
        addItem(many, "i2", "나", 1000, 1);
        assertThat(many.orderName()).hasSize(100).endsWith(" 외 1건");
    }
}
