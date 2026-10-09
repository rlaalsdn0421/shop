package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.entity.ProductValidation;
import com.shop.backend.presentation.dto.ProductDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int DEFAULT_PAGE_SIZE = 8;
    private static final int DEFAULT_BEST_SIZE = 4;

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ProductDtos.ProductPage listProducts(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        ProductSort productSort = ProductSort.parse(sort);
        ProductValidation.validatePage(page, size);
        return ProductDtos.ProductPage.from(productService.listProducts(category, productSort, page, size));
    }

    @GetMapping("/best")
    public ProductDtos.BestProducts bestProducts(
            @RequestParam(required = false) String period,
            @RequestParam(defaultValue = "" + DEFAULT_BEST_SIZE) int size) {
        BestPeriod bestPeriod = BestPeriod.parse(period);
        ProductValidation.validateBestSize(size);
        return ProductDtos.BestProducts.from(productService.listBestProducts(bestPeriod, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDtos.ProductDetail> getProduct(@PathVariable String id) {
        return productService.getProduct(id)
                .map(ProductDtos.ProductDetail::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
