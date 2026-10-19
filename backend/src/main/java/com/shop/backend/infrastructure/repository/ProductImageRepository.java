package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, String> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(String productId);

    /**
     * Bulk delete, runs at once (unlike a derived delete, which Hibernate defers behind the inserts and would hit
     * UNIQUE (product_id, sort_order)). No clear: it would detach the locked product.
     */
    @Modifying
    @Query("DELETE FROM ProductImage i WHERE i.productId = :productId")
    int deleteAllByProductId(@Param("productId") String productId);
}
