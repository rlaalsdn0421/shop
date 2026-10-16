package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
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
    private final ProductOptionRepository productOptionRepository;
    private final EntityManager em;
    private final Clock clock;

    public PaymentTransactions(OrderRepository orderRepository, ProductRepository productRepository,
                               ProductOptionRepository productOptionRepository, EntityManager em, Clock clock) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.productOptionRepository = productOptionRepository;
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

        if (order.isPastPayment()) {
            // changed since the unlocked read in PaymentService (cancel/ship won the race): leave it alone
            logger.info("Payment confirmation ignored, the order has moved on: orderId={} status={}", orderId,
                    order.getStatus());
            return order.getStatus();
        }
        if (Order.STATUS_EXPIRED.equals(order.getStatus())) {
            List<OrderItem> items = sortedByStockKey(order);
            int reserved = 0;
            for (OrderItem item : items) {
                if (reserveStock(item) == 0) {
                    break;
                }
                reserved++;
            }
            if (reserved < items.size()) {
                for (OrderItem item : items.subList(0, reserved)) {
                    releaseStock(productRepository, productOptionRepository, item);
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
        restoreStock(productRepository, productOptionRepository, order);
        return true;
    }

    /** What step 1 of a cancel found: the status after it, and whether the provider still has to be asked. */
    public record CancelStart(String status, boolean callProvider) {
    }

    /** What a finished cancel did (the outcome and the status after it). */
    public record CancelApplied(String status, Order.CancelOutcome outcome) {
    }

    /**
     * Cancel step 1 (customer: {@code userId} set; seller refund: {@code userId} null). Locks the order row and moves
     * PAID (or SHIPPED/DELIVERED for a refund) -> CANCELING, or reports that it is already done / is resumed. A foreign
     * or missing order is a 404. Runs under the same row lock as shipping, so only one of "cancel" and "ship" wins.
     */
    @Transactional
    public CancelStart beginCancel(String orderId, String userId, String reason) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        boolean customer = userId != null;
        if (customer && (userId.isBlank() || !userId.equals(order.getUserId()))) {
            throw new OrderNotFoundException();
        }
        boolean callProvider = customer ? order.beginCustomerCancel(reason) : order.beginRefund(reason);
        return new CancelStart(order.getStatus(), callProvider);
    }

    /** The provider refused the cancel: CANCELING goes back to where it came from (no-op if it is not CANCELING any more). */
    @Transactional
    public void revertCancel(String orderId) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        orderRepository.findByIdForUpdate(orderId).ifPresent(Order::revertCancel);
    }

    /**
     * The provider reports the payment cancelled (our own call, or the webhook for a console cancel): locks the row,
     * moves the order to CANCELLED/REFUNDED and gives the stock back (products and options in StockKey order) when it was paid and unshipped.
     * Idempotent: an order that is already final is left alone.
     */
    @Transactional
    public CancelApplied applyCancelled(String orderId) {
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
        Order.CancelOutcome outcome = order.finishCancel(Instant.now(clock));
        if (outcome == Order.CancelOutcome.RESTORE_STOCK) {
            restoreStock(productRepository, productOptionRepository, order);
        }
        return new CancelApplied(order.getStatus(), outcome);
    }

    /**
     * Gives an order's stock back, rows in StockKey order (shared with OrderService's replay-of-an-overdue-order path):
     * a line with an option returns to that option, a line without one to its product.
     */
    public static void restoreStock(ProductRepository productRepository, ProductOptionRepository productOptionRepository,
                                    Order order) {
        for (OrderItem item : sortedByStockKey(order)) {
            releaseStock(productRepository, productOptionRepository, item);
        }
    }

    private static void releaseStock(ProductRepository productRepository, ProductOptionRepository productOptionRepository,
                                     OrderItem item) {
        if (item.getOptionId() == null) {
            productRepository.incrementStock(item.getProduct().getId(), item.getQuantity());
        } else {
            productOptionRepository.incrementStock(item.getOptionId(), item.getQuantity());
        }
    }

    /** Takes the line's stock again; 0 = not enough left (nothing changed). */
    private int reserveStock(OrderItem item) {
        return item.getOptionId() == null
                ? productRepository.decrementStockIfAvailable(item.getProduct().getId(), item.getQuantity())
                : productOptionRepository.decrementStockIfAvailable(item.getOptionId(), item.getProduct().getId(),
                        item.getQuantity());
    }

    // By StockKey, like order creation: every stock update row-locks its row, so one fixed order avoids deadlocks.
    private static List<OrderItem> sortedByStockKey(Order order) {
        return order.getItems().stream().sorted(Comparator.comparing(OrderItem::stockKey)).toList();
    }
}
