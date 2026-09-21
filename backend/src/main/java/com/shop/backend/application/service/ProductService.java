package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductValidation;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public Product createProduct(String name, String description, Integer price, String imageUrl, Integer stock, String category) {
        String normalizedCategory = category == null || category.trim().isEmpty() ? null : category.trim();
        ProductValidation.validateNewProduct(name, description, price, imageUrl, stock, normalizedCategory);
        return productRepository.save(new Product(name, description, price, imageUrl, stock, normalizedCategory));
    }

    @Transactional(readOnly = true)
    public Optional<Product> getProduct(String id) {
        return productRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Product> listProducts(String category, int page, int size) {
        String trimmed = category == null ? null : category.trim();
        PageRequest pageRequest = PageRequest.of(page, size);
        return trimmed == null || trimmed.isBlank()
                ? productRepository.findAllByOrderByCreatedAtDescIdDesc(pageRequest)
                : productRepository.findAllByCategoryOrderByCreatedAtDescIdDesc(trimmed, pageRequest);
    }

    @Transactional(readOnly = true)
    public List<Product> listAdminProducts() {
        return productRepository.findAllByOrderByCreatedAtDesc();
    }
}
