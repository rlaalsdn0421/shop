package com.shop.backend.domain.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    /** The only status orders get today; product sales rankings count only these. */
    public static final String STATUS_PAID = "PAID";

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
    private String status = STATUS_PAID;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // JPA
    }

    public Order(String userId, String idempotencyKey, String requestHash,
                 String customerName, String customerPhone, String customerAddress, Integer totalAmount,
                 Integer shippingFee) {
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

    public List<OrderItem> getItems() {
        return items;
    }
}
