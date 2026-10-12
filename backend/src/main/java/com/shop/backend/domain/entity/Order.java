package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.PaymentNeedsReviewException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    /** Placed, stock reserved, waiting for payment. */
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    /** Payment verified with the provider; product sales rankings count only these. */
    public static final String STATUS_PAID = "PAID";
    /** Not paid within the window; stock was given back. */
    public static final String STATUS_EXPIRED = "EXPIRED";
    /** Paid after expiry but the stock was already gone: an operator must cancel the payment at the provider. */
    public static final String STATUS_PAYMENT_REVIEW = "PAYMENT_REVIEW";

    /** How long a new order holds its stock while waiting for payment. */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(20);

    private static final int TRANSACTION_ID_MAX = 100;
    private static final int ORDER_NAME_MAX = 100;

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    // NULL only for orders placed before V10
    @Column(name = "user_id", length = 36, updatable = false)
    private String userId;

    @Column(name = "idempotency_key", length = 64, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, updatable = false)
    private String requestHash;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 50)
    private String customerPhone;

    @Column(name = "customer_address", nullable = false, length = 500)
    private String customerAddress;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    // Already included in totalAmount (items + fee); 0 for orders placed before V14.
    @Column(name = "shipping_fee", nullable = false, updatable = false)
    private Integer shippingFee;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING_PAYMENT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // NULL for orders placed before V15 (they were never payable).
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_transaction_id", length = TRANSACTION_ID_MAX)
    private String paymentTransactionId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // JPA
    }

    /**
     * TESTS ONLY. Creates a PENDING_PAYMENT order with {@code expiresAt = null}: the expiry sweeper
     * (expires_at &lt; now) never picks it up, so it would hold its stock forever. Production code must use the
     * constructor that takes {@code now}.
     */
    public Order(String userId, String idempotencyKey, String requestHash,
                 String customerName, String customerPhone, String customerAddress, Integer totalAmount,
                 Integer shippingFee) {
        this(userId, idempotencyKey, requestHash, customerName, customerPhone, customerAddress, totalAmount,
                shippingFee, null);
    }

    /** A new order starts PENDING_PAYMENT and must be paid within {@link #PAYMENT_WINDOW} of {@code now}. */
    public Order(String userId, String idempotencyKey, String requestHash,
                 String customerName, String customerPhone, String customerAddress, Integer totalAmount,
                 Integer shippingFee, Instant now) {
        this.expiresAt = now == null ? null : now.plus(PAYMENT_WINDOW);
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.customerAddress = customerAddress;
        this.totalAmount = totalAmount;
        this.shippingFee = shippingFee;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.assignOrder(this);
    }

    /**
     * Idempotent: PAID stays PAID. From EXPIRED the caller must already have re-reserved the stock. A PAYMENT_REVIEW
     * order is never paid automatically.
     */
    public void markPaid(Instant now, String transactionId) {
        if (STATUS_PAID.equals(status)) {
            return;
        }
        if (STATUS_PAYMENT_REVIEW.equals(status)) {
            throw new PaymentNeedsReviewException();
        }
        status = STATUS_PAID;
        paidAt = now;
        paymentTransactionId = transactionId == null || transactionId.length() <= TRANSACTION_ID_MAX
                ? transactionId : transactionId.substring(0, TRANSACTION_ID_MAX);
    }

    /** PENDING_PAYMENT -> EXPIRED once the deadline has passed. Returns false (and changes nothing) otherwise. */
    public boolean expire(Instant now) {
        if (!STATUS_PENDING_PAYMENT.equals(status) || expiresAt == null || !expiresAt.isBefore(now)) {
            return false;
        }
        status = STATUS_EXPIRED;
        return true;
    }

    /** EXPIRED -> PAYMENT_REVIEW: money was taken but the stock could not be reserved again. */
    public void markPaymentReview() {
        if (!STATUS_EXPIRED.equals(status)) {
            throw new IllegalStateException("Only an EXPIRED order can move to PAYMENT_REVIEW");
        }
        status = STATUS_PAYMENT_REVIEW;
    }

    /**
     * "Representative product" (largest line amount, ties by line id: same rule as the order list) plus "외 N건";
     * cut to 100 characters while keeping the suffix. Needs the items and their products loaded.
     */
    public String orderName() {
        Comparator<OrderItem> representative = Comparator
                .comparingLong((OrderItem item) -> (long) item.getPrice() * item.getQuantity()).reversed()
                .thenComparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        String name = items.stream().min(representative).map(i -> i.getProduct().getName()).orElse("주문");
        String suffix = items.size() > 1 ? " 외 " + (items.size() - 1) + "건" : "";
        int room = ORDER_NAME_MAX - suffix.length();
        if (name.codePointCount(0, name.length()) > room) {
            name = name.substring(0, name.offsetByCodePoints(0, room));
        }
        return name + suffix;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public String getCustomerAddress() {
        return customerAddress;
    }

    public Integer getTotalAmount() {
        return totalAmount;
    }

    public Integer getShippingFee() {
        return shippingFee;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getPaymentTransactionId() {
        return paymentTransactionId;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
