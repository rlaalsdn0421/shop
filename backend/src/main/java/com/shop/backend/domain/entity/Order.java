package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
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
    /** A provider cancel was requested and its result is not final yet; {@code cancelFrom} is the status to go back to. */
    public static final String STATUS_CANCELING = "CANCELING";
    /** Cancelled and refunded BEFORE shipping; the stock was given back. */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** The seller started the delivery. */
    public static final String STATUS_SHIPPED = "SHIPPED";
    public static final String STATUS_DELIVERED = "DELIVERED";
    /** Refunded AFTER shipping; stock is not restored automatically (the seller adjusts it after inspecting the return). */
    public static final String STATUS_REFUNDED = "REFUNDED";

    /** Every status the database allows (orders_status_valid, V16). */
    public static final List<String> STATUSES = List.of(STATUS_PENDING_PAYMENT, STATUS_PAID, STATUS_EXPIRED,
            STATUS_PAYMENT_REVIEW, STATUS_CANCELING, STATUS_CANCELLED, STATUS_SHIPPED, STATUS_DELIVERED, STATUS_REFUNDED);

    /** What the caller of {@link #finishCancel} still has to do. */
    public enum CancelOutcome {
        /** Now CANCELLED from a paid order: give the stock back. */
        RESTORE_STOCK,
        /** Now CANCELLED/REFUNDED, stock untouched (refund after shipping, or a payment-review order that never held stock). */
        NO_STOCK_CHANGE,
        /** Was already CANCELLED/REFUNDED: nothing changed. */
        ALREADY_FINAL,
        /** PENDING_PAYMENT/EXPIRED: nothing was paid through us, nothing changed. */
        IGNORED
    }

    /** How long a new order holds its stock while waiting for payment. */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(20);

    private static final int TRANSACTION_ID_MAX = 100;
    private static final int ORDER_NAME_MAX = 100;
    public static final int REASON_MAX = 200;

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

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    // Set when the order ends CANCELLED or REFUNDED.
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    // Only while CANCELING (and kept for the record otherwise): the status to return to if the provider cancel fails.
    @Column(name = "cancel_from", length = 20)
    private String cancelFrom;

    @Column(name = "cancel_reason", length = REASON_MAX)
    private String cancelReason;

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
     * True once the order has been paid and moved on (CANCELING, CANCELLED, SHIPPED, DELIVERED, REFUNDED): a late or
     * repeated "payment confirmed" must never touch it.
     */
    public boolean isPastPayment() {
        return STATUS_CANCELING.equals(status) || STATUS_CANCELLED.equals(status) || STATUS_SHIPPED.equals(status)
                || STATUS_DELIVERED.equals(status) || STATUS_REFUNDED.equals(status);
    }

    /**
     * Legal only from PENDING_PAYMENT and EXPIRED (then the caller must already have re-reserved the stock).
     * Idempotent: PAID stays PAID. A PAYMENT_REVIEW order is never paid automatically; any later status is refused.
     */
    public void markPaid(Instant now, String transactionId) {
        if (STATUS_PAID.equals(status)) {
            return;
        }
        if (STATUS_PAYMENT_REVIEW.equals(status)) {
            throw new PaymentNeedsReviewException();
        }
        if (!STATUS_PENDING_PAYMENT.equals(status) && !STATUS_EXPIRED.equals(status)) {
            throw new InvalidOrderStateException("결제를 확정할 수 없는 주문 상태예요.");
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

    /** PAID -> SHIPPED. */
    public void ship(Instant now) {
        if (!STATUS_PAID.equals(status)) {
            throw new InvalidOrderStateException("결제가 완료된 주문만 배송을 시작할 수 있어요.");
        }
        status = STATUS_SHIPPED;
        shippedAt = now;
    }

    /** SHIPPED -> DELIVERED. */
    public void deliver(Instant now) {
        if (!STATUS_SHIPPED.equals(status)) {
            throw new InvalidOrderStateException("배송 중인 주문만 배송 완료로 바꿀 수 있어요.");
        }
        status = STATUS_DELIVERED;
        deliveredAt = now;
    }

    /**
     * Customer cancel, first step: PAID -> CANCELING. Returns true when the provider cancel must (still) be done, also
     * when a CANCELING order of this kind is resumed after an unknown outcome; false when it is already CANCELLED.
     * Anything else is refused (409): after shipping the customer has to ask the shop.
     */
    public boolean beginCustomerCancel(String reason) {
        if (STATUS_CANCELLED.equals(status)) {
            return false;
        }
        if (STATUS_PAID.equals(status)) {
            enterCanceling(reason);
            return true;
        }
        if (STATUS_CANCELING.equals(status) && STATUS_PAID.equals(cancelFrom)) {
            return true;
        }
        if (STATUS_SHIPPED.equals(status) || STATUS_DELIVERED.equals(status) || STATUS_REFUNDED.equals(status)
                || STATUS_CANCELING.equals(status)) {
            throw new OrderNotCancellableException("배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요.");
        }
        throw new OrderNotCancellableException("결제가 완료된 주문만 취소할 수 있어요.");
    }

    /**
     * Seller refund, first step: SHIPPED/DELIVERED -> CANCELING. Returns true when the provider cancel must (still) be
     * done, false when it is already REFUNDED. Also resumes ANY stuck CANCELING order, including one a customer started
     * from PAID (provider timeout, killed instance, failed revert): finishCancel then yields CANCELLED + stock back.
     * A plain PAID order is still refused (the customer cancels those, or the seller ships them).
     */
    public boolean beginRefund(String reason) {
        if (STATUS_REFUNDED.equals(status)) {
            return false;
        }
        if (STATUS_SHIPPED.equals(status) || STATUS_DELIVERED.equals(status)) {
            enterCanceling(reason);
            return true;
        }
        if (STATUS_CANCELING.equals(status)) {
            return true;
        }
        throw new InvalidOrderStateException("배송이 시작된 주문만 환불할 수 있어요.");
    }

    private void enterCanceling(String reason) {
        cancelFrom = status;
        cancelReason = reason;
        status = STATUS_CANCELING;
    }

    /** The provider refused the cancel: CANCELING -> the status it came from. Returns false (no change) otherwise. */
    public boolean revertCancel() {
        if (!STATUS_CANCELING.equals(status)) {
            return false;
        }
        if (cancelFrom == null) {
            throw new IllegalStateException("CANCELING order without a valid cancel_from");
        }
        status = cancelFrom;
        cancelFrom = null;
        cancelReason = null;
        return true;
    }

    /**
     * The provider says the payment is cancelled in full (our own cancel call, or the seller cancelled it in the
     * provider console). PAID, and CANCELING from PAID -> CANCELLED; SHIPPED/DELIVERED, and CANCELING from those ->
     * REFUNDED; PAYMENT_REVIEW -> CANCELLED (it never held stock). The result tells the caller whether to restore stock.
     */
    public CancelOutcome finishCancel(Instant now) {
        String result;
        CancelOutcome outcome;
        switch (status) {
            case STATUS_PAID -> {
                result = STATUS_CANCELLED;
                outcome = CancelOutcome.RESTORE_STOCK;
            }
            case STATUS_CANCELING -> {
                if (STATUS_PAID.equals(cancelFrom)) {
                    result = STATUS_CANCELLED;
                    outcome = CancelOutcome.RESTORE_STOCK;
                } else if (STATUS_SHIPPED.equals(cancelFrom) || STATUS_DELIVERED.equals(cancelFrom)) {
                    result = STATUS_REFUNDED;
                    outcome = CancelOutcome.NO_STOCK_CHANGE;
                } else {
                    throw new IllegalStateException("CANCELING order without a valid cancel_from");
                }
            }
            case STATUS_SHIPPED, STATUS_DELIVERED -> {
                result = STATUS_REFUNDED;
                outcome = CancelOutcome.NO_STOCK_CHANGE;
            }
            case STATUS_PAYMENT_REVIEW -> {
                result = STATUS_CANCELLED;
                outcome = CancelOutcome.NO_STOCK_CHANGE;
            }
            case STATUS_CANCELLED, STATUS_REFUNDED -> {
                return CancelOutcome.ALREADY_FINAL;
            }
            default -> {
                return CancelOutcome.IGNORED;
            }
        }
        status = result;
        cancelledAt = now;
        return outcome;
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

    public Instant getShippedAt() {
        return shippedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelFrom() {
        return cancelFrom;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
