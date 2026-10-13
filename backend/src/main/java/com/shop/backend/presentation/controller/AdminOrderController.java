package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.presentation.dto.OrderDtos;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Seller/admin order handling; SecurityConfig restricts /api/admin/orders/** to ADMIN and SELLER. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final OrderCancelService orderCancelService;

    public AdminOrderController(OrderService orderService, OrderCancelService orderCancelService) {
        this.orderService = orderService;
        this.orderCancelService = orderCancelService;
    }

    @GetMapping
    public ResponseEntity<OrderDtos.AdminOrderListResponse> listOrders(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OrderDtos.AdminOrderListResponse.from(orderService.listAdminOrders(status, size, cursor)));
    }

    @PostMapping("/{id}/ship")
    public OrderDtos.OrderStatusResponse ship(@PathVariable String id) {
        return new OrderDtos.OrderStatusResponse(orderService.shipOrder(id));
    }

    @PostMapping("/{id}/deliver")
    public OrderDtos.OrderStatusResponse deliver(@PathVariable String id) {
        return new OrderDtos.OrderStatusResponse(orderService.deliverOrder(id));
    }

    @PostMapping("/{id}/refund")
    public OrderDtos.OrderStatusResponse refund(@PathVariable String id,
                                                @RequestBody(required = false) OrderDtos.CancelRequest request) {
        return new OrderDtos.OrderStatusResponse(
                orderCancelService.refundByAdmin(id, request == null ? null : request.reason()));
    }
}
