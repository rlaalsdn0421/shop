package com.shop.backend.presentation;

import com.shop.backend.domain.Product;

import java.time.Instant;
import java.util.List;

/** Response/request shapes for the product endpoints, matching the storefront's API contract. */
public final class ProductDtos {

    private ProductDtos() {
    }

    public record ProductListItem(String id, String name, Integer price, String imageUrl) {
        static ProductListItem from(Product p) {
            return new ProductListItem(p.getId(), p.getName(), p.getPrice(), p.getImageUrl());
        }

        static List<ProductListItem> from(List<Product> products) {
            return products.stream().map(ProductListItem::from).toList();
        }
    }

    public record ProductDetail(String id, String name, Integer price, String imageUrl, String description, Integer stock) {
        static ProductDetail from(Product p) {
            return new ProductDetail(p.getId(), p.getName(), p.getPrice(), p.getImageUrl(), p.getDescription(), p.getStock());
        }
    }

    public record AdminProduct(String id, String name, Integer price, Integer stock, Instant createdAt) {
        static AdminProduct from(Product p) {
            return new AdminProduct(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getCreatedAt());
        }

        static List<AdminProduct> from(List<Product> products) {
            return products.stream().map(AdminProduct::from).toList();
        }
    }

    public record NewProductRequest(String name, String description, Integer price, String imageUrl, Integer stock, String category) {
    }

    public record IdResponse(String id) {
    }
}
