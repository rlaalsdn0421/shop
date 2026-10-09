package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
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

    private final EntityManager em;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository, EntityManager em) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.em = em;
    }

    @Transactional
    public Order createOrder(String customerName, String customerPhone, String customerAddress, List<OrderLine> lines) {
        OrderValidation.validateCustomerInfo(customerName, customerPhone, customerAddress);
        OrderValidation.validateOrderLines(lines);
        Map<String, Integer> quantityByProductId = OrderValidation.aggregateQuantities(lines);

        // Transaction-scoped (needs this transaction, resets at commit/rollback; fine behind a connection pooler): a
        // stuck lock holder fails this order after 3 s (-> 503) instead of tying up one of the few pooled connections.
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();

        List<Product> products = productRepository.findAllByIdIn(List.copyOf(quantityByProductId.keySet()));
        Map<String, Product> productsById = products.stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));

        for (String productId : quantityByProductId.keySet()) {
            if (!productsById.containsKey(productId)) {
                throw new ProductNotFoundException(productId);
            }
        }

        // Sorted by id, not request order: each update row-locks its product until commit, so two orders with the
        // same products in opposite order would deadlock.
        TreeMap<String, Integer> sortedQuantities = new TreeMap<>(quantityByProductId);

        // Pass 1, nothing locked yet: reject sold-out products early and make sure the amounts fit into an int.
        // The stock read here is advisory only (it can already be stale); the conditional UPDATE below decides.
        int totalAmount = 0;
        try {
            for (Map.Entry<String, Integer> entry : sortedQuantities.entrySet()) {
                Product product = productsById.get(entry.getKey());
                if (product.getStock() < entry.getValue()) {
                    throw new InsufficientStockException(product.getName());
                }
                totalAmount = Math.addExact(totalAmount, Math.multiplyExact(product.getPrice(), entry.getValue()));
            }
        } catch (ArithmeticException e) {
            throw new ValidationException("주문 금액이 너무 커요.");
        }

        // Pass 2: the stock check and decrement are ONE atomic UPDATE in the database; checking in Java and writing
        // the new absolute value would lose concurrent orders (the V1 CHECK stock >= 0 is only the last-resort
        // backstop). A failed update throws, which rolls back the whole transaction (DomainException is a
        // RuntimeException), undoing the products already decremented.
        Order order = new Order(customerName, customerPhone, customerAddress, totalAmount);
        for (Map.Entry<String, Integer> entry : sortedQuantities.entrySet()) {
            Product product = productsById.get(entry.getKey());
            int quantity = entry.getValue();
            if (productRepository.decrementStockIfAvailable(product.getId(), quantity) == 0) {
                throw new InsufficientStockException(product.getName());
            }
            order.addItem(new OrderItem(product, quantity, product.getPrice()));
        }

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
