package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderCursor;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.entity.ShippingPolicy;
import com.shop.backend.domain.entity.StockKey;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final OrderRepository orderRepository;

    private final EntityManager em;
    private final Clock clock;

    public OrderService(ProductRepository productRepository, ProductOptionRepository productOptionRepository,
                        OrderRepository orderRepository, EntityManager em, Clock clock) {
        this.productRepository = productRepository;
        this.productOptionRepository = productOptionRepository;
        this.orderRepository = orderRepository;
        this.em = em;
        this.clock = clock;
    }

    @Transactional
    public Order createOrder(String userId, String idempotencyKey, String customerName, String customerPhone,
                             String customerAddress, List<OrderLine> lines) {
        OrderValidation.validateIdempotencyKey(idempotencyKey);
        OrderValidation.validateCustomerInfo(customerName, customerPhone, customerAddress);
        OrderValidation.validateOrderLines(lines);
        Map<StockKey, Integer> quantityByKey = OrderValidation.aggregateLines(lines);
        String requestHash = OrderValidation.requestHashByStockKey(customerName, customerPhone, customerAddress, quantityByKey);

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
            Order replay = existing.get();
            Instant now = Instant.now(clock);
            if (Order.STATUS_PENDING_PAYMENT.equals(replay.getStatus()) && replay.getExpiresAt() != null
                    && replay.getExpiresAt().isBefore(now)) {
                // Overdue but the sweeper has not run yet: the client would keep replaying a dead order, so expire it
                // here (the client sees EXPIRED and re-keys). Lock order: advisory lock (held), this order row, stock
                // rows (products and options) in StockKey order. Lock first, THEN re-read: the copy loaded above may predate a payment that committed while we
                // waited for the lock, and expiring a just-paid order would wrongly return its stock. (refresh(entity,
                // lockMode) is not enough: Hibernate 6 reads first with a plain SELECT and only then locks.)
                em.lock(replay, LockModeType.PESSIMISTIC_WRITE);
                em.refresh(replay);
                if (replay.expire(now)) {
                    PaymentTransactions.restoreStock(productRepository, productOptionRepository, replay);
                }
            }
            // otherwise a replay changes no stock and saves nothing; the current status is returned as is
            replay.getItems().forEach(item -> item.getProduct().getName()); // orderName() is read outside the tx
            return replay;
        }

        List<String> productIds = quantityByKey.keySet().stream().map(StockKey::productId).distinct().toList();
        List<Product> products = productRepository.findAllByIdIn(productIds);
        Map<String, Product> productsById = products.stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));

        for (String productId : productIds) {
            if (!productsById.containsKey(productId)) {
                throw new ProductNotFoundException(productId);
            }
        }

        // The server decides which products sell by option: a product with options needs a valid optionId of its own,
        // one without options takes none (the option's price and stock replace the product's below).
        Map<String, List<ProductOption>> optionsByProductId = productOptionRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(ProductOption::getProductId));
        Map<StockKey, ProductOption> optionByKey = new HashMap<>();
        for (StockKey key : quantityByKey.keySet()) {
            List<ProductOption> options = optionsByProductId.getOrDefault(key.productId(), List.of());
            if (key.optionId() == null) {
                if (!options.isEmpty()) {
                    throw new ValidationException("옵션을 선택해 주세요.");
                }
                continue;
            }
            ProductOption option = options.stream().filter(o -> o.getId().equals(key.optionId())).findFirst()
                    .orElseThrow(() -> new ValidationException("선택한 옵션을 찾을 수 없어요."));
            optionByKey.put(key, option);
        }

        // Sorted by StockKey (productId, optionId), not request order: each update row-locks its stock row until
        // commit, so two orders touching the same rows in opposite order would deadlock. Same order in every path.
        TreeMap<StockKey, Integer> sortedQuantities = new TreeMap<>(quantityByKey);

        // Pass 1, nothing locked yet: reject sold-out products early and make sure the amounts fit into an int.
        // The stock read here is advisory only (it can already be stale); the conditional UPDATE below decides.
        int itemsAmount = 0;
        int shippingFee;
        int totalAmount;
        try {
            for (Map.Entry<StockKey, Integer> entry : sortedQuantities.entrySet()) {
                Product product = productsById.get(entry.getKey().productId());
                ProductOption option = optionByKey.get(entry.getKey());
                int stock = option != null ? option.getStock() : product.getStock();
                int unitPrice = option != null ? option.getPrice() : product.getPrice();
                if (stock < entry.getValue()) {
                    throw new InsufficientStockException(soldOutName(product, option));
                }
                itemsAmount = Math.addExact(itemsAmount, Math.multiplyExact(unitPrice, entry.getValue()));
            }
            shippingFee = ShippingPolicy.feeFor(itemsAmount);
            totalAmount = Math.addExact(itemsAmount, shippingFee);
        } catch (ArithmeticException e) {
            throw new ValidationException("주문 금액이 너무 커요.");
        }

        // Pass 2: the stock check and decrement are ONE atomic UPDATE in the database; checking in Java and writing
        // the new absolute value would lose concurrent orders (the V1 CHECK stock >= 0 is only the last-resort
        // backstop). A failed update throws, which rolls back the whole transaction (DomainException is a
        // RuntimeException), undoing the products already decremented.
        Order order = new Order(userId, idempotencyKey, requestHash, customerName, customerPhone, customerAddress, totalAmount,
                shippingFee, Instant.now(clock));
        for (Map.Entry<StockKey, Integer> entry : sortedQuantities.entrySet()) {
            Product product = productsById.get(entry.getKey().productId());
            ProductOption option = optionByKey.get(entry.getKey());
            int quantity = entry.getValue();
            if (option == null) {
                if (productRepository.decrementStockIfAvailable(product.getId(), quantity) == 0) {
                    throw new InsufficientStockException(product.getName());
                }
                order.addItem(new OrderItem(product, quantity, product.getPrice()));
            } else {
                if (productOptionRepository.decrementStockIfAvailable(option.getId(), product.getId(), quantity) == 0) {
                    throw new InsufficientStockException(soldOutName(product, option));
                }
                order.addItem(new OrderItem(product, quantity, option.getPrice(), option.getId(), option.label()));
            }
        }

        return orderRepository.save(order);
    }

    private static String soldOutName(Product product, ProductOption option) {
        return option == null ? product.getName() : product.getName() + " (" + option.label() + ")";
    }

    /** One list row; the aggregates are computed in memory from the page's lines (no per-order query). */
    public record OrderSummary(Order order, int itemCount, String firstItemName, String firstItemOptionLabel) {
        public OrderSummary(Order order, int itemCount, String firstItemName) {
            this(order, itemCount, firstItemName, null);
        }
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
        return toPage(fetched, size);
    }

    /** Admin list of every user's orders, newest first; {@code status} null/blank = all, otherwise a known status. */
    @Transactional(readOnly = true)
    public OrderPage listAdminOrders(String status, int size, String cursor) {
        OrderValidation.validateListSize(size);
        String statusFilter = OrderValidation.normalizeStatusFilter(status);
        OrderCursor position = cursor == null ? null : OrderCursor.decode(cursor);
        PageRequest limit = PageRequest.of(0, size + 1);
        List<Order> fetched = position == null
                ? orderRepository.findFirstPageForAdmin(statusFilter, limit)
                : orderRepository.findPageForAdminAfter(statusFilter, position.createdAt(), position.id(), limit);
        return toPage(fetched, size);
    }

    /** PAID -> SHIPPED under the order row lock (a customer cancel locks the same row, so only one of them wins). */
    @Transactional
    public String shipOrder(String orderId) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        order.ship(Instant.now(clock));
        return order.getStatus();
    }

    /** SHIPPED -> DELIVERED under the order row lock. */
    @Transactional
    public String deliverOrder(String orderId) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        order.deliver(Instant.now(clock));
        return order.getStatus();
    }

    private OrderPage toPage(List<Order> fetched, int size) {
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
            OrderItem first = items.stream().min(representative).orElse(null);
            return new OrderSummary(order, items.size(), first == null ? null : first.getProduct().getName(),
                    first == null ? null : first.getOptionLabel());
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
