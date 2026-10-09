package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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

        for (String productId : quantityByProductId.keySet()) {
            if (!productsById.containsKey(productId)) {
                throw new ProductNotFoundException(productId);
            }
        }

        int totalAmount = 0;
        Order order = new Order(customerName, customerPhone, customerAddress, 0);
        // Sorted by id, not request order: each update row-locks its product until commit, so two orders with the
        // same products in opposite order would deadlock. The failed update throws, which rolls back the whole
        // transaction (DomainException is a RuntimeException), undoing the products already decremented.
        for (Map.Entry<String, Integer> entry : new TreeMap<>(quantityByProductId).entrySet()) {
            Product product = productsById.get(entry.getKey());
            int quantity = entry.getValue();
            // The stock check and decrement are ONE atomic UPDATE in the database; checking in Java and writing the
            // new absolute value would lose concurrent orders (the V1 CHECK stock >= 0 is only the last-resort backstop).
            if (productRepository.decrementStockIfAvailable(product.getId(), quantity) == 0) {
                throw new InsufficientStockException(product.getName());
            }
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
