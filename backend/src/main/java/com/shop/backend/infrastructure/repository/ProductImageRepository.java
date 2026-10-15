package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductImageRepository extends JpaRepository<ProductImage, String> {

    List<ProductImage> findByProductIdOrderBySortOrderAsc(String productId);
}
