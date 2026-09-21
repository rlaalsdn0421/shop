package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByOrderByCreatedAtDesc();

    List<Product> findAllByCategoryOrderByCreatedAtDesc(String category);

    List<Product> findAllByIdIn(List<String> ids);
}
