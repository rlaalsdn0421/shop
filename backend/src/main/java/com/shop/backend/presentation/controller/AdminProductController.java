package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.presentation.dto.ProductDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductDtos.AdminProduct> listAdminProducts() {
        return ProductDtos.AdminProduct.from(productService.listAdminProducts());
    }

    @PostMapping
    public ProductDtos.IdResponse createProduct(@RequestBody ProductDtos.NewProductRequest request) {
        var product = productService.createProduct(
                request.name(), request.description(), request.price(), request.imageUrl(), request.stock(), request.category(),
                request.originalPrice(), request.hashtags());
        return new ProductDtos.IdResponse(product.getId());
    }
}
