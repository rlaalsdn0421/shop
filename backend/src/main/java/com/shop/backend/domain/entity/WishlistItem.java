package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** One product a user wished for. Rows are only written by WishlistRepository's native insert (created_at has a DB default). */
@Entity
@Table(name = "wishlist_items")
public class WishlistItem {

    @EmbeddedId
    private WishlistItemId id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WishlistItem() {
        // JPA
    }

    public WishlistItemId getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Embeddable
    public static class WishlistItemId implements Serializable {

        @Column(name = "user_id", length = 36, nullable = false)
        private String userId;

        @Column(name = "product_id", length = 36, nullable = false)
        private String productId;

        protected WishlistItemId() {
            // JPA
        }

        public WishlistItemId(String userId, String productId) {
            this.userId = userId;
            this.productId = productId;
        }

        public String getUserId() {
            return userId;
        }

        public String getProductId() {
            return productId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof WishlistItemId other
                    && Objects.equals(userId, other.userId) && Objects.equals(productId, other.productId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, productId);
        }
    }
}
