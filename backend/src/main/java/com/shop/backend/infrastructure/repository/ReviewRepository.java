package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, String> {

    List<Review> findByProductIdOrderByCreatedAtDesc(String productId);

    boolean existsByProductIdAndUserId(String productId, String userId);

    /**
     * Masks the reviewer name on a withdrawn user's reviews; text, rating and user_id stay (aggregates, 1 review each).
     * Neither flush nor clear on purpose: no Review entity is loaded in the withdrawing transaction, so there is
     * nothing stale to detach. The affected-row count is not needed by the caller.
     */
    @Modifying
    @Query("UPDATE Review r SET r.reviewerName = :name WHERE r.userId = :userId")
    int renameReviewer(@Param("userId") String userId, @Param("name") String name);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.product.id = :productId")
    Double averageRatingByProductId(@Param("productId") String productId);
}
