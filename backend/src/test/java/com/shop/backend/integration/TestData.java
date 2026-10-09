package com.shop.backend.integration;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds rows through the real entities (the production write path), overriding the id / createdAt that
 * @PrePersist would generate so tests can hand-compute rankings and create identical timestamps like the seed rows.
 */
final class TestData {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final EntityManager em;
    private final TransactionTemplate tx;

    TestData(EntityManager em, PlatformTransactionManager txManager) {
        this.em = em;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Product with a fixed id and createdAt; originalPrice null = no discount, category null = uncategorized. */
    void product(String id, String category, int price, Integer originalPrice, Instant createdAt) {
        Product product = new Product("상품 " + id, "설명", price, "http://img/" + id, 100, category, originalPrice, null);
        ReflectionTestUtils.setField(product, "id", id);
        ReflectionTestUtils.setField(product, "createdAt", createdAt);
        tx.executeWithoutResult(s -> em.persist(product));
    }

    /** One order with a single line of {@code quantity} units; status is PAID or CANCELLED. */
    void sale(String productId, String status, int quantity, Instant orderCreatedAt) {
        tx.executeWithoutResult(s -> {
            Order order = new Order("구매자", "010-0000-0000", "서울", 1000 * quantity);
            ReflectionTestUtils.setField(order, "status", status);
            ReflectionTestUtils.setField(order, "createdAt", orderCreatedAt);
            order.addItem(new OrderItem(em.getReference(Product.class, productId), quantity, 1000));
            em.persist(order);
        });
    }

    /** Anonymous (legacy, user_id NULL) review. */
    void review(String productId, int rating) {
        review(productId, null, rating);
    }

    void review(String productId, String userId, int rating) {
        tx.executeWithoutResult(s -> em.persist(
                new Review(em.getReference(Product.class, productId), userId, "작성자", rating, "좋아요")));
    }

    /** Regular member with a unique random username/email. */
    String user() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return user("user_" + suffix, suffix + "@example.com");
    }

    String user(String username, String email) {
        User user = new User(username, email, "hash", Role.USER);
        tx.executeWithoutResult(s -> em.persist(user));
        return user.getId();
    }
}
