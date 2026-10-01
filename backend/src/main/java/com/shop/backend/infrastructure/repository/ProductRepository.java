package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findAllByOrderByCreatedAtDesc();

    // createdAt alone isn't unique (seed rows share the same timestamp), so ties are
    // broken by id — otherwise consecutive pages can return overlapping/duplicate rows.
    Page<Product> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<Product> findAllByCategoryOrderByCreatedAtDescIdDesc(String category, Pageable pageable);

    List<Product> findAllByIdIn(List<String> ids);
}
