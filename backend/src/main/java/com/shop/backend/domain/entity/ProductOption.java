package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One sellable color/size variant of a product with its own price and stock (V18); products without rows sell as before. */
@Entity
@Table(name = "product_options")
public class ProductOption {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "product_id", length = 36, nullable = false, updatable = false)
    private String productId;

    @Column(name = "color_name", length = 30)
    private String colorName;

    @Column(name = "color_hex", length = 7)
    private String colorHex;

    @Column(name = "size_label", length = 30)
    private String sizeLabel;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "original_price")
    private Integer originalPrice;

    @Column(nullable = false)
    private Integer stock;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductOption() {
        // JPA
    }

    /** {@code option} must already be cleaned by {@link ProductValidation#normalizeOptions}. */
    public ProductOption(String productId, NewProductOption option, int sortOrder) {
        this.productId = productId;
        this.colorName = option.colorName();
        this.colorHex = option.colorHex();
        this.sizeLabel = option.sizeLabel();
        this.price = option.price();
        this.originalPrice = option.originalPrice();
        this.stock = option.stock();
        this.sortOrder = sortOrder;
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

    public String getProductId() {
        return productId;
    }

    public String getColorName() {
        return colorName;
    }

    public String getColorHex() {
        return colorHex;
    }

    public String getSizeLabel() {
        return sizeLabel;
    }

    public Integer getPrice() {
        return price;
    }

    public Integer getOriginalPrice() {
        return originalPrice;
    }

    public Integer getStock() {
        return stock;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public Integer getDiscountRate() {
        return Product.discountRate(originalPrice, price);
    }

    /** "화이트 / M": color then size, only the parts present. Stored on the order line as a snapshot. */
    public String label() {
        if (colorName != null && sizeLabel != null) {
            return colorName + " / " + sizeLabel;
        }
        return colorName != null ? colorName : sizeLabel;
    }
}
