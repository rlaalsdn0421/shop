package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderCursor;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Seller order list (keyset pagination, status filter) and ship/deliver, plain Mockito. */
@ExtendWith(MockitoExtension.class)
class OrderServiceAdminTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private ProductRepository productRepository;
    @Mock
    private OrderRepository orderRepository;
    private final EntityManager em = mock(EntityManager.class, Answers.RETURNS_DEEP_STUBS);

    private OrderService service() {
        return new OrderService(productRepository, orderRepository, em, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order order(String id, String status, Instant createdAt) {
        Order order = new Order("user-" + id, "k-" + id, "h", "구매자", "010", "서울", 1000, 0);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }

    private static OrderItem line(Order order, String itemId, String name) {
        OrderItem item = new OrderItem(new Product(name, "설명", 1000, "http://img/x", 5), 1, 1000);
        ReflectionTestUtils.setField(item, "id", itemId);
        order.addItem(item);
        return item;
    }

    // list

    @Test
    void 성공_첫_페이지는_모든_사용자의_주문을_size_더하기_1개로_조회하고_커서를_만든다() {
        Instant t = Instant.parse("2026-10-11T01:02:03.123456Z");
        Order o1 = order("o3", Order.STATUS_PAID, t.plusSeconds(2));
        Order o2 = order("o2", Order.STATUS_SHIPPED, t.plusSeconds(1));
        Order o3 = order("o1", Order.STATUS_PAID, t);
        line(o1, "i1", "A");
        line(o2, "i2", "B");
        when(orderRepository.findFirstPageForAdmin(null, PageRequest.of(0, 3))).thenReturn(List.of(o1, o2, o3));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o3", "o2")))
                .thenReturn(List.of(o1.getItems().get(0), o2.getItems().get(0)));

        OrderService.OrderPage page = service().listAdminOrders(null, 2, null);

        assertThat(page.orders()).extracting(s -> s.order().getId()).containsExactly("o3", "o2");
        assertThat(page.orders()).extracting(OrderService.OrderSummary::firstItemName).containsExactly("A", "B");
        assertThat(OrderCursor.decode(page.nextCursor())).isEqualTo(new OrderCursor(t.plusSeconds(1), "o2"));
        verify(orderRepository, never()).findPageForAdminAfter(any(), any(), any(), any());
    }

    @Test
    void 성공_마지막_페이지는_nextCursor가_null이고_상태_필터를_그대로_넘긴다() {
        Order o1 = order("o1", Order.STATUS_PAID, Instant.parse("2026-10-11T00:00:00Z"));
        line(o1, "i1", "A");
        when(orderRepository.findFirstPageForAdmin("PAID", PageRequest.of(0, 21))).thenReturn(List.of(o1));
        when(orderRepository.findItemsWithProductByOrderIds(List.of("o1"))).thenReturn(o1.getItems());

        OrderService.OrderPage page = service().listAdminOrders("PAID", 20, null);

        assertThat(page.orders()).hasSize(1);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 성공_커서가_있으면_커서_조건_쿼리로_디코딩한_값을_넘기고_빈_상태_필터는_전체다() {
        Instant t = Instant.parse("2026-10-11T01:02:03.123456Z");
        when(orderRepository.findPageForAdminAfter(null, t, "o2", PageRequest.of(0, 11))).thenReturn(List.of());

        OrderService.OrderPage page = service().listAdminOrders(" ", 10, new OrderCursor(t, "o2").encode());

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        verify(orderRepository, never()).findFirstPageForAdmin(any(), any());
    }

    @Test
    void 실패_알려지지_않은_상태_잘못된_크기_깨진_커서는_조회_전에_400이다() {
        assertThatThrownBy(() -> service().listAdminOrders("NOPE", 20, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service().listAdminOrders(null, 0, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service().listAdminOrders(null, 51, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service().listAdminOrders(null, 20, "garbage!")).isInstanceOf(ValidationException.class);

        verifyNoInteractions(orderRepository);
    }

    // ship / deliver

    @Test
    void 성공_배송_시작은_행을_잠근_뒤_결제완료를_배송중으로_바꾼다() {
        Order order = order("o1", Order.STATUS_PAID, NOW);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(service().shipOrder("o1")).isEqualTo(Order.STATUS_SHIPPED);

        assertThat(order.getShippedAt()).isEqualTo(NOW);
        InOrder inOrder = inOrder(em, orderRepository);
        inOrder.verify(em).createNativeQuery("SET LOCAL lock_timeout = '3s'");
        inOrder.verify(orderRepository).findByIdForUpdate("o1");
    }

    @Test
    void 실패_고객이_먼저_취소해_취소중이_된_주문은_배송을_시작할_수_없다() {
        Order order = order("o1", Order.STATUS_PAID, NOW);
        order.beginCustomerCancel(null);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service().shipOrder("o1")).isInstanceOf(InvalidOrderStateException.class);
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_CANCELING);
    }

    @Test
    void 실패_없는_주문의_배송_시작과_배송_완료는_404다() {
        when(orderRepository.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().shipOrder("nope")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> service().deliverOrder("nope")).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void 성공_배송_완료는_배송중을_배송완료로_바꾸고_시각을_남긴다() {
        Order order = order("o1", Order.STATUS_SHIPPED, NOW);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(order));

        assertThat(service().deliverOrder("o1")).isEqualTo(Order.STATUS_DELIVERED);
        assertThat(order.getDeliveredAt()).isEqualTo(NOW);
    }

    @Test
    void 실패_배송중이_아닌_주문의_배송_완료는_409이고_이미_배송중인_주문을_다시_시작할_수도_없다() {
        Order paid = order("o1", Order.STATUS_PAID, NOW);
        Order shipped = order("o2", Order.STATUS_SHIPPED, NOW);
        when(orderRepository.findByIdForUpdate("o1")).thenReturn(Optional.of(paid));
        when(orderRepository.findByIdForUpdate("o2")).thenReturn(Optional.of(shipped));

        assertThatThrownBy(() -> service().deliverOrder("o1")).isInstanceOf(InvalidOrderStateException.class);
        assertThatThrownBy(() -> service().shipOrder("o2")).isInstanceOf(InvalidOrderStateException.class);
    }
}
