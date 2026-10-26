package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.WishlistItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WishlistRepository extends JpaRepository<WishlistItem, WishlistItem.WishlistItemId> {

    /** Idempotent add done by the database: returns 1 when a row was inserted, 0 when the user already had it. */
    @Modifying
    @Query(value = "INSERT INTO wishlist_items (user_id, product_id, created_at) VALUES (:userId, :productId, now()) "
            + "ON CONFLICT (user_id, product_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") String userId, @Param("productId") String productId);

    @Modifying
    @Query("DELETE FROM WishlistItem w WHERE w.id.userId = :userId AND w.id.productId = :productId")
    int deleteByUserAndProduct(@Param("userId") String userId, @Param("productId") String productId);

    @Modifying
    @Query("DELETE FROM WishlistItem w WHERE w.id.userId = :userId")
    int deleteAllByUser(@Param("userId") String userId);

    @Query("SELECT COUNT(w) FROM WishlistItem w WHERE w.id.userId = :userId")
    long countByUser(@Param("userId") String userId);

    @Query("SELECT w.id.productId FROM WishlistItem w WHERE w.id.userId = :userId "
            + "ORDER BY w.createdAt DESC, w.id.productId")
    List<String> findProductIdsByUser(@Param("userId") String userId, Pageable pageable);

    // Newest wish first; the Pageable only caps the size (unsorted: the order is in the query).
    @Query("SELECT p FROM WishlistItem w JOIN Product p ON p.id = w.id.productId WHERE w.id.userId = :userId "
            + "ORDER BY w.createdAt DESC, p.id")
    List<Product> findWishedProductsByUser(@Param("userId") String userId, Pageable pageable);
}
