package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;
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

    /** Regular price before the discount; null = no discount. The discount rate is derived, never stored. */
    @Column(name = "original_price")
    private Integer originalPrice;

    /**
     * Normalized tags (no '#') joined by one space. shortcut: display-only, so one column and no join table;
     * upgrade to a product_tags table if tag search/click-through is added.
     */
    @Column(length = 100)
    private String hashtags;

    protected Product() {
        // JPA
    }

    public Product(String name, String description, Integer price, String imageUrl, Integer stock) {
        this(name, description, price, imageUrl, stock, null);
    }

    public Product(String name, String description, Integer price, String imageUrl, Integer stock, String category) {
        this(name, description, price, imageUrl, stock, category, null, null);
    }

    /** {@code hashtags} must already be normalized by {@link ProductValidation#normalizeHashtags}. */
    public Product(String name, String description, Integer price, String imageUrl, Integer stock, String category,
                   Integer originalPrice, List<String> hashtags) {
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.stock = stock;
        this.category = category;
        this.originalPrice = originalPrice;
        this.hashtags = hashtags == null || hashtags.isEmpty() ? null : String.join(" ", hashtags);
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

    public Integer getOriginalPrice() {
        return originalPrice;
    }

    /** Tags without '#'; empty list (never null) when none. */
    public List<String> getHashtags() {
        return hashtags == null || hashtags.isEmpty() ? List.of() : List.of(hashtags.split(" "));
    }

    /**
     * Discount percent, rounded down; null when there is no original price or it rounds to 0%.
     * The "discount" sort in ProductRepository.DISCOUNT_RATE must keep the same formula.
     * long math: (originalPrice - price) * 100 overflows int for prices above ~21M.
     */
    public Integer getDiscountRate() {
        if (originalPrice == null || originalPrice <= price) {
            return null;
        }
        long rate = ((long) originalPrice - price) * 100 / originalPrice;
        return rate >= 1 ? (int) rate : null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
