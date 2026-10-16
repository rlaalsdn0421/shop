package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Integer price;

    /** Plain id, not a relation: restoring stock only needs the key, and the label below is the display snapshot. */
    @Column(name = "option_id", length = 36, updatable = false)
    private String optionId;

    /** "화이트 / M" at order time; kept even if the option changes later. */
    @Column(name = "option_label", length = 80, updatable = false)
    private String optionLabel;

    protected OrderItem() {
        // JPA
    }

    public OrderItem(Product product, Integer quantity, Integer price) {
        this(product, quantity, price, null, null);
    }

    public OrderItem(Product product, Integer quantity, Integer price, String optionId, String optionLabel) {
        this.product = product;
        this.quantity = quantity;
        this.price = price;
        this.optionId = optionId;
        this.optionLabel = optionLabel;
    }

    void assignOrder(Order order) {
        this.order = order;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public Product getProduct() {
        return product;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public Integer getPrice() {
        return price;
    }

    public String getOptionId() {
        return optionId;
    }

    public String getOptionLabel() {
        return optionLabel;
    }

    /** The stock row this line reserved: the option's when it has one, else the product's. */
    public StockKey stockKey() {
        return new StockKey(product.getId(), optionId);
    }
}
