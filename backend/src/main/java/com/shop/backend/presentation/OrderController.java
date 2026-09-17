package com.shop.backend.presentation;

import com.shop.backend.application.OrderService;
import com.shop.backend.domain.OrderLine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderDtos.OrderIdResponse createOrder(@RequestBody OrderDtos.OrderRequest request) {
        var lines = request.items() == null ? null
                : request.items().stream().map(i -> new OrderLine(i.productId(), i.quantity())).toList();
        var order = orderService.createOrder(
                request.customerName(), request.customerPhone(), request.customerAddress(), lines);
        return new OrderDtos.OrderIdResponse(order.getId());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDtos.OrderView> getOrder(@PathVariable String id) {
        return orderService.getOrder(id)
                .map(OrderDtos.OrderView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
