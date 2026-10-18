package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Order status transitions of the ship / cancel / refund flow (design: docs/refund-design.md section 1). */
class OrderCancelTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static Order withStatus(String status) {
        Order order = new Order("u", "k", "h", "구매자", "010", "서울", 1000, 0, NOW);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    /** A CANCELING order that came from {@code from} (PAID by the customer, SHIPPED/DELIVERED by the seller). */
    private static Order canceling(String from) {
        Order order = withStatus(from);
        if (Order.STATUS_PAID.equals(from)) {
            order.beginCustomerCancel(null);
        } else {
            order.beginRefund("사유");
        }
        return order;
    }

    // ship / deliver

    @Test
    void 성공_결제완료_주문을_배송중으로_바꾸고_시각을_남긴다() {
        Order order = withStatus(Order.STATUS_PAID);
        order.ship(NOW);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_SHIPPED);
        assertThat(order.getShippedAt()).isEqualTo(NOW);
    }

    @Test
    void 실패_결제완료가_아닌_주문은_배송을_시작할_수_없다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_EXPIRED, Order.STATUS_PAYMENT_REVIEW,
                Order.STATUS_CANCELING, Order.STATUS_CANCELLED, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED,
                Order.STATUS_REFUNDED)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.ship(NOW)).as(status).isInstanceOf(InvalidOrderStateException.class);
            assertThat(order.getStatus()).isEqualTo(status);
            assertThat(order.getShippedAt()).isNull();
        }
    }

    @Test
    void 성공_배송중_주문을_배송완료로_바꾸고_시각을_남긴다() {
        Order order = withStatus(Order.STATUS_SHIPPED);
        order.deliver(NOW);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_DELIVERED);
        assertThat(order.getDeliveredAt()).isEqualTo(NOW);
    }

    @Test
    void 실패_배송중이_아닌_주문은_배송완료로_바꿀_수_없다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_PAID, Order.STATUS_CANCELING,
                Order.STATUS_CANCELLED, Order.STATUS_DELIVERED, Order.STATUS_REFUNDED)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.deliver(NOW)).as(status).isInstanceOf(InvalidOrderStateException.class);
        }
    }

    // customer cancel

    @Test
    void 성공_고객_취소는_결제완료를_취소중으로_바꾸고_직전_상태와_사유를_남긴다() {
        Order order = withStatus(Order.STATUS_PAID);

        assertThat(order.beginCustomerCancel("마음이 바뀜")).isTrue();

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        assertThat(order.getCancelReason()).isEqualTo("마음이 바뀜");
    }

    @Test
    void 성공_이미_취소된_주문의_고객_취소는_할_일_없음으로_멱등이다() {
        Order order = withStatus(Order.STATUS_CANCELLED);
        assertThat(order.beginCustomerCancel(null)).isFalse();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void 성공_결제완료에서_시작된_취소중_주문은_고객_취소를_이어서_처리한다() {
        Order order = canceling(Order.STATUS_PAID);
        assertThat(order.beginCustomerCancel("다시")).isTrue();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
        assertThat(order.getCancelReason()).isNull(); // the first request's reason is kept
    }

    @Test
    void 실패_배송이_시작된_주문은_고객이_취소할_수_없고_연락처_안내가_나온다() {
        for (String status : List.of(Order.STATUS_SHIPPED, Order.STATUS_DELIVERED, Order.STATUS_REFUNDED)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.beginCustomerCancel(null)).as(status)
                    .isInstanceOf(OrderNotCancellableException.class)
                    .hasMessageContaining("배송이 시작되어");
            assertThat(order.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void 실패_배송_후_환불이_진행_중인_취소중_주문은_고객이_이어받을_수_없다() {
        Order order = canceling(Order.STATUS_SHIPPED);
        assertThatThrownBy(() -> order.beginCustomerCancel(null)).isInstanceOf(OrderNotCancellableException.class);
    }

    @Test
    void 실패_결제되지_않은_주문은_고객이_취소할_수_없다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_EXPIRED, Order.STATUS_PAYMENT_REVIEW)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.beginCustomerCancel(null)).as(status)
                    .isInstanceOf(OrderNotCancellableException.class)
                    .hasMessageContaining("결제가 완료된 주문만");
        }
    }

    // seller refund

    @Test
    void 성공_결제완료_배송중_배송완료_주문의_판매자_취소_환불은_취소중으로_바꾸고_직전_상태와_사유를_남긴다() {
        for (String from : List.of(Order.STATUS_PAID, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED)) {
            Order order = withStatus(from);
            assertThat(order.beginRefund("하자")).as(from).isTrue();
            assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
            assertThat(order.getCancelFrom()).isEqualTo(from);
            assertThat(order.getCancelReason()).isEqualTo("하자");
        }
    }

    @Test
    void 성공_이미_환불되거나_취소된_주문의_판매자_환불은_할_일_없음으로_멱등이고_취소중이면_이어서_처리한다() {
        for (String done : List.of(Order.STATUS_REFUNDED, Order.STATUS_CANCELLED)) {
            Order order = withStatus(done);
            assertThat(order.beginRefund("x")).as(done).isFalse();
            assertThat(order.getStatus()).as(done).isEqualTo(done);
            assertThat(order.getCancelFrom()).as(done).isNull();
            assertThat(order.getCancelReason()).as(done).isNull();
        }
        assertThat(canceling(Order.STATUS_DELIVERED).beginRefund("x")).isTrue();
    }

    @Test
    void 실패_결제_전이거나_만료_결제확인_상태의_주문은_판매자_환불을_거부하고_상태는_그대로다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_EXPIRED, Order.STATUS_PAYMENT_REVIEW)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.beginRefund("x")).as(status).isInstanceOf(InvalidOrderStateException.class)
                    .hasMessage("결제가 완료된 주문만 취소·환불할 수 있어요.");
            assertThat(order.getStatus()).as(status).isEqualTo(status);
            assertThat(order.getCancelFrom()).as(status).isNull();
        }
    }

    @Test
    void 성공_판매자_환불은_9개_상태_모두에서_정해진_대로_동작한다() {
        for (String status : Order.STATUSES) {
            Order order = withStatus(status);
            if (status.equals(Order.STATUS_CANCELING)) {
                ReflectionTestUtils.setField(order, "cancelFrom", Order.STATUS_PAID);
            }
            boolean accepted = List.of(Order.STATUS_PAID, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED, Order.STATUS_CANCELING,
                    Order.STATUS_REFUNDED, Order.STATUS_CANCELLED).contains(status);
            if (!accepted) {
                assertThatThrownBy(() -> order.beginRefund("x")).as(status).isInstanceOf(InvalidOrderStateException.class);
                continue;
            }
            boolean callProvider = !status.equals(Order.STATUS_REFUNDED) && !status.equals(Order.STATUS_CANCELLED);
            assertThat(order.beginRefund("x")).as(status).isEqualTo(callProvider);
        }
        assertThat(Order.STATUSES).hasSize(9);
    }

    @Test
    void 성공_결제완료_판매자_취소는_고객_취소와_같이_CANCELLED와_재고_복구로_끝난다() {
        Order seller = withStatus(Order.STATUS_PAID);
        Order customer = withStatus(Order.STATUS_PAID);

        assertThat(seller.beginRefund("품절")).isTrue();
        assertThat(customer.beginCustomerCancel("품절")).isTrue();

        assertThat(seller.finishCancel(NOW)).isEqualTo(customer.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.RESTORE_STOCK);
        assertThat(seller.getStatus()).isEqualTo(Order.STATUS_CANCELLED).isEqualTo(customer.getStatus());
        assertThat(seller.getCancelledAt()).isEqualTo(NOW);
        assertThat(seller.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        // a second webhook / retry changes nothing and restores nothing
        assertThat(seller.finishCancel(NOW.plusSeconds(60))).isEqualTo(Order.CancelOutcome.ALREADY_FINAL);
        assertThat(seller.getCancelledAt()).isEqualTo(NOW);
    }

    @Test
    void 성공_배송_후_판매자_환불은_REFUNDED이고_재고_복구_없음이다() {
        for (String from : List.of(Order.STATUS_SHIPPED, Order.STATUS_DELIVERED)) {
            Order order = withStatus(from);
            order.beginRefund("하자");

            assertThat(order.finishCancel(NOW)).as(from).isEqualTo(Order.CancelOutcome.NO_STOCK_CHANGE);
            assertThat(order.getStatus()).as(from).isEqualTo(Order.STATUS_REFUNDED);
        }
    }

    @Test
    void 실패_판매자가_취소를_시작한_뒤에는_배송을_시작할_수_없다() {
        Order order = withStatus(Order.STATUS_PAID);
        order.beginRefund("품절");

        assertThatThrownBy(() -> order.ship(NOW)).isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
        order.finishCancel(NOW);
        assertThatThrownBy(() -> order.ship(NOW)).isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void 성공_판매자가_취소를_시작한_PAID_주문을_고객이_이어받아_취소해도_같은_결과다() {
        Order order = withStatus(Order.STATUS_PAID);
        order.beginRefund("품절");

        assertThat(order.beginCustomerCancel("고객")).isTrue();
        assertThat(order.getCancelReason()).isEqualTo("품절");
        assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.RESTORE_STOCK);
    }

    @Test
    void 성공_판매자_환불은_고객이_시작했다가_멈춘_결제완료발_취소중_주문도_이어서_처리한다() {
        Order order = canceling(Order.STATUS_PAID);

        assertThat(order.beginRefund("고객 취소가 멈춤")).isTrue();

        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.RESTORE_STOCK);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void 성공_결제완료_주문은_판매자가_바로_취소를_시작할_수_있다() {
        Order order = withStatus(Order.STATUS_PAID);

        assertThat(order.beginRefund("품절")).isTrue();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
        assertThat(order.getCancelFrom()).isEqualTo(Order.STATUS_PAID);
        assertThat(order.getCancelReason()).isEqualTo("품절");
    }

    // revert

    @Test
    void 성공_포트원이_취소를_거절하면_취소중이_직전_상태로_돌아간다() {
        for (String from : List.of(Order.STATUS_PAID, Order.STATUS_SHIPPED, Order.STATUS_DELIVERED)) {
            Order order = canceling(from);
            assertThat(order.revertCancel()).as(from).isTrue();
            assertThat(order.getStatus()).isEqualTo(from);
            assertThat(order.getCancelFrom()).isNull();
            assertThat(order.getCancelReason()).isNull();
        }
    }

    @Test
    void 실패_취소중이_아니면_되돌릴_것이_없다() {
        Order order = withStatus(Order.STATUS_CANCELLED);
        assertThat(order.revertCancel()).isFalse();
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void 실패_직전_상태가_없는_취소중_주문은_되돌리지_않고_상태를_null로_만들지_않는다() {
        Order order = withStatus(Order.STATUS_CANCELING);
        assertThatThrownBy(order::revertCancel).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
    }

    // markPaid is legal only before the payment (PENDING_PAYMENT / EXPIRED)

    @Test
    void 성공_결제_대기와_만료_주문만_결제완료로_바뀌고_결제완료는_그대로다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_EXPIRED)) {
            Order order = withStatus(status);
            order.markPaid(NOW, "tx-1");
            assertThat(order.getStatus()).as(status).isEqualTo(Order.STATUS_PAID);
            assertThat(order.getPaidAt()).isEqualTo(NOW);
        }
        Order paid = withStatus(Order.STATUS_PAID);
        paid.markPaid(NOW, "tx-2");
        assertThat(paid.getStatus()).isEqualTo(Order.STATUS_PAID);
        assertThat(paid.getPaidAt()).isNull(); // untouched
        assertThat(paid.getPaymentTransactionId()).isNull();
    }

    @Test
    void 실패_결제_확인_필요_주문은_markPaid가_기존대로_예외다() {
        Order order = withStatus(Order.STATUS_PAYMENT_REVIEW);
        assertThatThrownBy(() -> order.markPaid(NOW, "tx")).isInstanceOf(PaymentNeedsReviewException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PAYMENT_REVIEW);
    }

    @Test
    void 실패_결제_이후_상태의_주문은_markPaid로_결제완료로_돌아가지_못한다() {
        for (String status : List.of(Order.STATUS_CANCELING, Order.STATUS_CANCELLED, Order.STATUS_SHIPPED,
                Order.STATUS_DELIVERED, Order.STATUS_REFUNDED)) {
            Order order = withStatus(status);
            assertThatThrownBy(() -> order.markPaid(NOW, "tx")).as(status)
                    .isInstanceOf(InvalidOrderStateException.class);
            assertThat(order.getStatus()).isEqualTo(status);
            assertThat(order.getPaidAt()).isNull();
            assertThat(order.getPaymentTransactionId()).isNull();
        }
    }

    @Test
    void 성공_결제_이후_상태만_isPastPayment가_참이다() {
        for (String status : Order.STATUSES) {
            boolean expected = List.of(Order.STATUS_CANCELING, Order.STATUS_CANCELLED, Order.STATUS_SHIPPED,
                    Order.STATUS_DELIVERED, Order.STATUS_REFUNDED).contains(status);
            assertThat(withStatus(status).isPastPayment()).as(status).isEqualTo(expected);
        }
    }

    // finish (our own cancel and the console webhook)

    @Test
    void 성공_결제완료와_결제완료에서_시작된_취소중은_취소됨이_되고_재고를_돌려줘야_한다() {
        for (Order order : List.of(withStatus(Order.STATUS_PAID), canceling(Order.STATUS_PAID))) {
            assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.RESTORE_STOCK);
            assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
            assertThat(order.getCancelledAt()).isEqualTo(NOW);
        }
    }

    @Test
    void 성공_배송_후_주문은_환불됨이_되고_재고는_건드리지_않는다() {
        for (Order order : List.of(withStatus(Order.STATUS_SHIPPED), withStatus(Order.STATUS_DELIVERED),
                canceling(Order.STATUS_SHIPPED), canceling(Order.STATUS_DELIVERED))) {
            assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.NO_STOCK_CHANGE);
            assertThat(order.getStatus()).isEqualTo(Order.STATUS_REFUNDED);
            assertThat(order.getCancelledAt()).isEqualTo(NOW);
        }
    }

    @Test
    void 성공_결제_검토_주문은_콘솔_취소로_취소됨이_되지만_재고는_건드리지_않는다() {
        Order order = withStatus(Order.STATUS_PAYMENT_REVIEW);
        assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.NO_STOCK_CHANGE);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELLED);
    }

    @Test
    void 성공_이미_취소_환불된_주문은_아무것도_바꾸지_않는다() {
        for (String status : List.of(Order.STATUS_CANCELLED, Order.STATUS_REFUNDED)) {
            Order order = withStatus(status);
            assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.ALREADY_FINAL);
            assertThat(order.getStatus()).isEqualTo(status);
            assertThat(order.getCancelledAt()).isNull();
        }
    }

    @Test
    void 성공_결제되지_않은_주문은_취소_통지에도_상태가_바뀌지_않는다() {
        for (String status : List.of(Order.STATUS_PENDING_PAYMENT, Order.STATUS_EXPIRED)) {
            Order order = withStatus(status);
            assertThat(order.finishCancel(NOW)).isEqualTo(Order.CancelOutcome.IGNORED);
            assertThat(order.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void 실패_직전_상태가_없는_취소중_주문은_마무리하지_않고_예외를_던진다() {
        Order order = withStatus(Order.STATUS_CANCELING);
        assertThatThrownBy(() -> order.finishCancel(NOW)).isInstanceOf(IllegalStateException.class);
    }

    // validation helpers

    @Test
    void 성공_취소_사유는_다듬고_선택이면_빈_값을_null로_돌려준다() {
        assertThat(OrderValidation.normalizeCancelReason("  단순 변심 ", false)).isEqualTo("단순 변심");
        assertThat(OrderValidation.normalizeCancelReason("  ", false)).isNull();
        assertThat(OrderValidation.normalizeCancelReason(null, false)).isNull();
        assertThat(OrderValidation.normalizeCancelReason("a".repeat(200), true)).hasSize(200);
    }

    @Test
    void 실패_필수_사유가_비었거나_200자를_넘으면_거부한다() {
        assertThatThrownBy(() -> OrderValidation.normalizeCancelReason(" ", true)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderValidation.normalizeCancelReason(null, true)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderValidation.normalizeCancelReason("a".repeat(201), false))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_주문_상태_필터는_알려진_상태만_받고_비어_있으면_전체다() {
        for (String status : Order.STATUSES) {
            assertThat(OrderValidation.normalizeStatusFilter(status)).isEqualTo(status);
        }
        assertThat(OrderValidation.normalizeStatusFilter(null)).isNull();
        assertThat(OrderValidation.normalizeStatusFilter("")).isNull();
    }

    @Test
    void 실패_알려지지_않은_주문_상태_필터는_거부한다() {
        for (String bad : List.of("paid", "UNKNOWN", "PAID ", "PAID' OR '1'='1")) {
            assertThatThrownBy(() -> OrderValidation.normalizeStatusFilter(bad)).as(bad)
                    .isInstanceOf(ValidationException.class);
        }
    }
}
