package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** One gallery image of a product; products.image_url stays the representative (first) image. */
@Entity
@Table(name = "product_images")
public class ProductImage {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "product_id", length = 36, nullable = false, updatable = false)
    private String productId;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected ProductImage() {
        // JPA
    }

    public ProductImage(String productId, String url, int sortOrder) {
        this.productId = productId;
        this.url = url;
        this.sortOrder = sortOrder;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getProductId() {
        return productId;
    }

    public String getUrl() {
        return url;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }
}
