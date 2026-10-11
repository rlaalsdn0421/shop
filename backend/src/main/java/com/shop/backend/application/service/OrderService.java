package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderCursor;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
    public Order createOrder(String userId, String idempotencyKey, String customerName, String customerPhone,
                             String customerAddress, List<OrderLine> lines) {
        OrderValidation.validateIdempotencyKey(idempotencyKey);
        OrderValidation.validateCustomerInfo(customerName, customerPhone, customerAddress);
        OrderValidation.validateOrderLines(lines);
        Map<String, Integer> quantityByProductId = OrderValidation.aggregateQuantities(lines);
        String requestHash = OrderValidation.requestHash(customerName, customerPhone, customerAddress, quantityByProductId);

        // Transaction-scoped (needs this transaction, resets at commit/rollback; fine behind a connection pooler): a
        // stuck lock holder fails this order after 3 s (-> 503) instead of tying up one of the few pooled connections.
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();

        // Serializes requests with the same user+key until commit, so the lookup below sees a concurrent twin's order
        // (READ COMMITTED) instead of both creating one; the unique index is only the last-resort backstop. The user
        // id is a UUID (no ':') and a key is [A-Za-z0-9_-], so two different user+key pairs cannot share one lock string.
        orderRepository.lockUserIdempotencyKey(userId, idempotencyKey);

        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            if (!requestHash.equals(existing.get().getRequestHash())) {
                throw new IdempotencyKeyReuseException();
            }
            return existing.get(); // replay: no stock change, nothing saved
        }

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
        Order order = new Order(userId, idempotencyKey, requestHash, customerName, customerPhone, customerAddress, totalAmount);
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

    /** One list row; the aggregates are computed in memory from the page's lines (no per-order query). */
    public record OrderSummary(Order order, int itemCount, String firstItemName) {
    }

    public record OrderPage(List<OrderSummary> orders, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public OrderPage listMyOrders(String userId, int size, String cursor) {
        OrderValidation.validateListSize(size);
        OrderCursor position = cursor == null ? null : OrderCursor.decode(cursor);
        // A null/blank userId must not reach the query (null would match every legacy user_id IS NULL order).
        if (userId == null || userId.isBlank()) {
            return new OrderPage(List.of(), null);
        }
        PageRequest limit = PageRequest.of(0, size + 1);
        List<Order> fetched = position == null
                ? orderRepository.findFirstPageByUser(userId, limit)
                : orderRepository.findPageByUserAfter(userId, position.createdAt(), position.id(), limit);
        boolean hasNext = fetched.size() > size;
        List<Order> page = hasNext ? fetched.subList(0, size) : fetched;
        if (page.isEmpty()) {
            return new OrderPage(List.of(), null);
        }

        Map<String, List<OrderItem>> itemsByOrderId = orderRepository
                .findItemsWithProductByOrderIds(page.stream().map(Order::getId).toList())
                .stream().collect(Collectors.groupingBy(item -> item.getOrder().getId()));
        // Largest line amount first, ties by line id: the same order always shows the same representative product.
        Comparator<OrderItem> representative = Comparator
                .comparingLong((OrderItem item) -> (long) item.getPrice() * item.getQuantity()).reversed()
                .thenComparing(OrderItem::getId);
        List<OrderSummary> summaries = page.stream().map(order -> {
            List<OrderItem> items = itemsByOrderId.getOrDefault(order.getId(), List.of());
            String firstName = items.stream().min(representative).map(i -> i.getProduct().getName()).orElse(null);
            return new OrderSummary(order, items.size(), firstName);
        }).toList();

        Order last = page.get(page.size() - 1);
        String nextCursor = hasNext ? new OrderCursor(last.getCreatedAt(), last.getId()).encode() : null;
        return new OrderPage(summaries, nextCursor);
    }

    @Transactional(readOnly = true)
    public Optional<Order> getOrder(String id, String userId) {
        // ponytail: open-in-view is off, so items and each item's product (both LAZY) must be
        // touched before the transaction/session closes, or the controller's DTO mapping throws.
        // Someone else's, missing and legacy (user_id NULL) orders all look the same: empty.
        // A null/blank userId (a null principal gives getName() "") must not reach the query: Spring Data would turn
        // null into "user_id IS NULL" = every legacy order.
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Optional<Order> order = orderRepository.findByIdAndUserId(id, userId);
        order.ifPresent(o -> o.getItems().forEach(item -> item.getProduct().getName()));
        return order;
    }
}
