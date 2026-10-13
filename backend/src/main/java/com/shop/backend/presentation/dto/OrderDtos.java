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

    /** {@code orderId} is the original field (kept for existing clients); {@code id} is the same value. */
    public record OrderIdResponse(String orderId, String id, String status, Integer totalAmount, String orderName,
                                  Instant expiresAt) {
        public static OrderIdResponse from(Order order) {
            return new OrderIdResponse(order.getId(), order.getId(), order.getStatus(), order.getTotalAmount(),
                    order.orderName(), order.getExpiresAt());
        }
    }

    public record PaymentCompleteResponse(String status) {
    }

    public record OrderItemView(String id, Integer quantity, Integer price, String productName) {
        static OrderItemView from(OrderItem item) {
            return new OrderItemView(item.getId(), item.getQuantity(), item.getPrice(), item.getProduct().getName());
        }
    }

    public record OrderSummaryView(String id, Instant createdAt, Integer totalAmount, String status, int itemCount,
                                    String firstItemName, Instant expiresAt, Instant paidAt, Instant shippedAt,
                                    Instant deliveredAt, Instant cancelledAt) {
    }

    public record OrderListResponse(List<OrderSummaryView> orders, String nextCursor) {
        public static OrderListResponse from(OrderService.OrderPage page) {
            return new OrderListResponse(page.orders().stream()
                    .map(s -> new OrderSummaryView(s.order().getId(), s.order().getCreatedAt(),
                            s.order().getTotalAmount(), s.order().getStatus(), s.itemCount(), s.firstItemName(),
                            s.order().getExpiresAt(), s.order().getPaidAt(), s.order().getShippedAt(),
                            s.order().getDeliveredAt(), s.order().getCancelledAt()))
                    .toList(), page.nextCursor());
        }
    }

    public record OrderView(String id, String status, Integer totalAmount, Integer shippingFee, String customerName,
                             String customerPhone, String customerAddress, Instant expiresAt, Instant paidAt,
                             Instant shippedAt, Instant deliveredAt, Instant cancelledAt, List<OrderItemView> items) {
        public static OrderView from(Order order) {
            List<OrderItemView> items = order.getItems().stream().map(OrderItemView::from).toList();
            return new OrderView(order.getId(), order.getStatus(), order.getTotalAmount(), order.getShippingFee(),
                    order.getCustomerName(), order.getCustomerPhone(), order.getCustomerAddress(), order.getExpiresAt(),
                    order.getPaidAt(), order.getShippedAt(), order.getDeliveredAt(), order.getCancelledAt(), items);
        }
    }

    /** Body of the customer cancel and the seller refund; {@code reason} is optional for the former, required for the latter. */
    public record CancelRequest(String reason) {
    }

    public record OrderStatusResponse(String status) {
    }

    /** One row of the seller's order list; carries the buyer's personal data, so the response is never cached. */
    public record AdminOrderView(String id, String status, Integer totalAmount, Integer shippingFee, Instant createdAt,
                                  Instant paidAt, Instant shippedAt, Instant deliveredAt, String customerName,
                                  String customerPhone, String customerAddress, int itemCount, String firstItemName) {
    }

    public record AdminOrderListResponse(List<AdminOrderView> orders, String nextCursor) {
        public static AdminOrderListResponse from(OrderService.OrderPage page) {
            return new AdminOrderListResponse(page.orders().stream()
                    .map(s -> new AdminOrderView(s.order().getId(), s.order().getStatus(), s.order().getTotalAmount(),
                            s.order().getShippingFee(), s.order().getCreatedAt(), s.order().getPaidAt(),
                            s.order().getShippedAt(), s.order().getDeliveredAt(), s.order().getCustomerName(),
                            s.order().getCustomerPhone(), s.order().getCustomerAddress(), s.itemCount(),
                            s.firstItemName()))
                    .toList(), page.nextCursor());
        }
    }
}
