package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.presentation.dto.OrderDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderDtos.OrderIdResponse createOrder(
            // not required=true: a missing header must give our Korean 400 from OrderValidation, not Spring's default
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OrderDtos.OrderRequest request, Authentication authentication) {
        var lines = request.items() == null ? null
                : request.items().stream().map(i -> new OrderLine(i.productId(), i.quantity())).toList();
        // JwtAuthFilter sets the user id as the principal (its name); SecurityConfig guarantees it is present
        var order = orderService.createOrder(authentication.getName(), idempotencyKey,
                request.customerName(), request.customerPhone(), request.customerAddress(), lines);
        return OrderDtos.OrderIdResponse.from(order);
    }

    @GetMapping
    public OrderDtos.OrderListResponse listMyOrders(
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String cursor, Authentication authentication) {
        return OrderDtos.OrderListResponse.from(orderService.listMyOrders(authentication.getName(), size, cursor));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDtos.OrderView> getOrder(@PathVariable String id, Authentication authentication) {
        return orderService.getOrder(id, authentication.getName())
                .map(OrderDtos.OrderView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
