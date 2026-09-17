package com.shop.backend.presentation;

import com.shop.backend.application.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public List<ProductDtos.ProductListItem> listProducts() {
        return ProductDtos.ProductListItem.from(productService.listProducts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDtos.ProductDetail> getProduct(@PathVariable String id) {
        return productService.getProduct(id)
                .map(ProductDtos.ProductDetail::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
