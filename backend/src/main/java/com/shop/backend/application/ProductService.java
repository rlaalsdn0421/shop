package com.shop.backend.application;

import com.shop.backend.domain.Product;
import com.shop.backend.domain.ProductValidation;
import com.shop.backend.infrastructure.ProductRepository;
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
        ProductValidation.validateNewProduct(name, description, price, imageUrl, stock, category);
        return productRepository.save(new Product(name, description, price, imageUrl, stock, category));
    }

    @Transactional(readOnly = true)
    public Optional<Product> getProduct(String id) {
        return productRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts(String category) {
        String trimmed = category == null ? null : category.trim();
        return trimmed == null || trimmed.isBlank()
                ? productRepository.findAllByOrderByCreatedAtDesc()
                : productRepository.findAllByCategoryOrderByCreatedAtDesc(trimmed);
    }

    @Transactional(readOnly = true)
    public List<Product> listAdminProducts() {
        return productRepository.findAllByOrderByCreatedAtDesc();
    }
}
