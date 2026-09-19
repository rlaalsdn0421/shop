package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.InsufficientStockException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "image_url", nullable = false, length = 2000)
    private String imageUrl;

    @Column(nullable = false)
    private Integer stock;

    @Column(length = 50)
    private String category;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Product() {
        // JPA
    }

    public Product(String name, String description, Integer price, String imageUrl, Integer stock) {
        this(name, description, price, imageUrl, stock, null);
    }

    public Product(String name, String description, Integer price, String imageUrl, Integer stock, String category) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.stock = stock;
        this.category = category;
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

    /**
     * Decrements stock by quantity.
     * ponytail: check-then-decrement isn't safe under concurrent orders for the same
     * product; the DB CHECK constraint (stock >= 0, see V1 migration) is the real
     * backstop. Add pessimistic row locking if overselling under load matters.
     */
    public void decrementStock(int quantity) {
        if (stock < quantity) {
            throw new InsufficientStockException(name);
        }
        stock -= quantity;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Integer getPrice() {
        return price;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Integer getStock() {
        return stock;
    }

    public String getCategory() {
        return category;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
