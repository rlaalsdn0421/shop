package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The short database transactions of the payment flow. A separate bean from PaymentService on purpose: the provider
 * call there must stay OUTSIDE any transaction, and @Transactional only works through the Spring proxy (a self-call
 * would silently run without a transaction, so the row lock would not hold).
 */
@Service
public class PaymentTransactions {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTransactions.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final EntityManager em;
    private final Clock clock;

    public PaymentTransactions(OrderRepository orderRepository, ProductRepository productRepository, EntityManager em,
                               Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.em = em;
        this.clock = clock;
    }

    /**
     * Locks the order row, then PENDING_PAYMENT/EXPIRED -> PAID (idempotent when already PAID). For an EXPIRED order
     * the stock is reserved again; if any product is short, what was reserved is given back and the order becomes
     * PAYMENT_REVIEW. Returns the resulting status (PAYMENT_REVIEW is returned, not thrown, so this commits).
     */
    @Transactional
    public String applyPaid(String orderId, String transactionId) {
        // Transaction-scoped, same as OrderService: a stuck lock holder fails this confirmation after 3 s (-> 503).
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        Instant now = Instant.now(clock);

        if (Order.STATUS_EXPIRED.equals(order.getStatus())) {
            List<OrderItem> items = sortedByProduct(order);
            int reserved = 0;
            for (OrderItem item : items) {
                if (productRepository.decrementStockIfAvailable(item.getProduct().getId(), item.getQuantity()) == 0) {
                    break;
                }
                reserved++;
            }
            if (reserved < items.size()) {
                for (OrderItem item : items.subList(0, reserved)) {
                    productRepository.incrementStock(item.getProduct().getId(), item.getQuantity());
                }
                order.markPaymentReview();
                logger.error("Payment arrived for an expired order but the stock is gone; cancel it at the provider: orderId={}",
                        orderId);
                return order.getStatus();
            }
        }
        order.markPaid(now, transactionId);
        return order.getStatus();
    }

    /**
     * Expires one order in its own transaction. SKIP LOCKED: if a payment confirmation holds the row we leave it
     * alone (it is probably about to be paid). The status/deadline are re-checked under the lock because the order
     * may have been paid since the candidate list was read. Returns whether it was expired.
     */
    @Transactional
    public boolean expireOne(String orderId) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdateSkipLocked(orderId).orElse(null);
        if (order == null || !order.expire(Instant.now(clock))) {
            return false;
        }
        restoreStock(productRepository, order);
        return true;
    }

    /** Gives an expired order's stock back, products in id order (shared with OrderService's replay-of-an-overdue-order path). */
    public static void restoreStock(ProductRepository productRepository, Order order) {
        for (OrderItem item : sortedByProduct(order)) {
            productRepository.incrementStock(item.getProduct().getId(), item.getQuantity());
        }
    }

    // By product id, like order creation: every stock update row-locks its product, so a fixed order avoids deadlocks.
    private static List<OrderItem> sortedByProduct(Order order) {
        return order.getItems().stream().sorted(Comparator.comparing(i -> i.getProduct().getId())).toList();
    }
}
