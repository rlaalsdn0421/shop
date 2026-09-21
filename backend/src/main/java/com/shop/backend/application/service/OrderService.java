package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public Order createOrder(String customerName, String customerPhone, String customerAddress, List<OrderLine> lines) {
        OrderValidation.validateCustomerInfo(customerName, customerPhone, customerAddress);
        OrderValidation.validateOrderLines(lines);
        Map<String, Integer> quantityByProductId = OrderValidation.aggregateQuantities(lines);

        List<Product> products = productRepository.findAllByIdIn(List.copyOf(quantityByProductId.keySet()));
        Map<String, Product> productsById = products.stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));

        int totalAmount = 0;
        Order order = new Order(customerName, customerPhone, customerAddress, 0);
        for (Map.Entry<String, Integer> entry : quantityByProductId.entrySet()) {
            Product product = productsById.get(entry.getKey());
            if (product == null) {
                throw new ProductNotFoundException(entry.getKey());
            }
            int quantity = entry.getValue();
            // ponytail: check-then-decrement isn't safe under concurrent orders for the
            // same product; the DB check constraint (stock >= 0) is the real backstop.
            product.decrementStock(quantity);
            totalAmount += product.getPrice() * quantity;
            order.addItem(new OrderItem(product, quantity, product.getPrice()));
        }

        order.setTotalAmount(totalAmount);
        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Optional<Order> getOrder(String id) {
        // ponytail: open-in-view is off, so items and each item's product (both LAZY) must be
        // touched before the transaction/session closes, or the controller's DTO mapping throws.
        Optional<Order> order = orderRepository.findById(id);
        order.ifPresent(o -> o.getItems().forEach(item -> item.getProduct().getName()));
        return order;
    }
}
