package com.shop.backend.presentation.dto;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;

import java.time.Instant;
import java.util.List;

/** Response/request shapes for the order endpoints, matching the storefront's API contract. */
public final class OrderDtos {

    private OrderDtos() {
    }

    public record OrderLineRequest(String productId, Integer quantity) {
    }

    public record OrderRequest(String customerName, String customerPhone, String customerAddress,
                                List<OrderLineRequest> items) {
    }

    public record OrderIdResponse(String orderId) {
    }

    public record OrderItemView(String id, Integer quantity, Integer price, String productName) {
        static OrderItemView from(OrderItem item) {
            return new OrderItemView(item.getId(), item.getQuantity(), item.getPrice(), item.getProduct().getName());
        }
    }

    public record OrderSummaryView(String id, Instant createdAt, Integer totalAmount, String status, int itemCount,
                                    String firstItemName) {
    }

    public record OrderListResponse(List<OrderSummaryView> orders, String nextCursor) {
        public static OrderListResponse from(OrderService.OrderPage page) {
            return new OrderListResponse(page.orders().stream()
                    .map(s -> new OrderSummaryView(s.order().getId(), s.order().getCreatedAt(),
                            s.order().getTotalAmount(), s.order().getStatus(), s.itemCount(), s.firstItemName()))
                    .toList(), page.nextCursor());
        }
    }

    public record OrderView(String id, Integer totalAmount, Integer shippingFee, String customerName,
                             String customerPhone, String customerAddress, List<OrderItemView> items) {
        public static OrderView from(Order order) {
            List<OrderItemView> items = order.getItems().stream().map(OrderItemView::from).toList();
            return new OrderView(order.getId(), order.getTotalAmount(), order.getShippingFee(), order.getCustomerName(),
                    order.getCustomerPhone(), order.getCustomerAddress(), items);
        }
    }
}
