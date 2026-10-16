package com.shop.backend.presentation.dto;

import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response/request shapes for the product endpoints, matching the storefront's API contract. */
public final class ProductDtos {

    private ProductDtos() {
    }

    /** {@code priceVaries}: the product has options with different prices, so {@code price} is the cheapest ("N원~"). */
    public record ProductListItem(String id, String name, Integer price, String imageUrl,
                                  Integer originalPrice, Integer discountRate, List<String> hashtags,
                                  boolean priceVaries) {
        static ProductListItem from(Product p) {
            return new ProductListItem(p.getId(), p.getName(), p.getPrice(), p.getImageUrl(),
                    p.getOriginalPrice(), p.getDiscountRate(), p.getHashtags(), p.isPriceVaries());
        }

        public static List<ProductListItem> from(List<Product> products) {
            return products.stream().map(ProductListItem::from).toList();
        }
    }

    public record ProductPage(List<ProductListItem> items, boolean hasMore) {
        public static ProductPage from(Page<Product> page) {
            return new ProductPage(ProductListItem.from(page.getContent()), page.hasNext());
        }
    }

    public record BestProducts(List<ProductListItem> items) {
        public static BestProducts from(List<Product> products) {
            return new BestProducts(ProductListItem.from(products));
        }
    }

    public record OptionView(String id, String colorName, String colorHex, String sizeLabel, Integer price,
                             Integer originalPrice, Integer discountRate, Integer stock) {
        static OptionView from(ProductOption o) {
            return new OptionView(o.getId(), o.getColorName(), o.getColorHex(), o.getSizeLabel(), o.getPrice(),
                    o.getOriginalPrice(), o.getDiscountRate(), o.getStock());
        }
    }

    /** {@code options} is empty (never null) for a product without options; then {@code price}/{@code stock} are what is sold. */
    public record ProductDetail(String id, String name, Integer price, String imageUrl, String description, Integer stock,
                                Integer originalPrice, Integer discountRate, List<String> hashtags, String category,
                                List<String> images, List<OptionView> options, boolean priceVaries) {
        public static ProductDetail from(Product p, List<String> images, List<ProductOption> options) {
            return new ProductDetail(p.getId(), p.getName(), p.getPrice(), p.getImageUrl(), p.getDescription(), p.getStock(),
                    p.getOriginalPrice(), p.getDiscountRate(), p.getHashtags(), p.getCategory(), images,
                    options.stream().map(OptionView::from).toList(), p.isPriceVaries());
        }
    }

    /** {@code stock}: for a product with options the sum of its option stock (products.stock is unused then). */
    public record AdminProduct(String id, String name, Integer price, Integer stock, Instant createdAt) {
        static AdminProduct from(Product p, Map<String, Long> optionStockByProductId) {
            Long optionStock = optionStockByProductId.get(p.getId());
            Integer stock = optionStock == null ? p.getStock() : (int) Math.min(optionStock, Integer.MAX_VALUE);
            return new AdminProduct(p.getId(), p.getName(), p.getPrice(), stock, p.getCreatedAt());
        }

        public static List<AdminProduct> from(List<Product> products, Map<String, Long> optionStockByProductId) {
            return products.stream().map(p -> from(p, optionStockByProductId)).toList();
        }
    }

    public record NewOptionRequest(String colorName, String colorHex, String sizeLabel, Integer price,
                                   Integer originalPrice, Integer stock) {
        public NewProductOption toDomain() {
            return new NewProductOption(colorName, colorHex, sizeLabel, price, originalPrice, stock);
        }
    }

    /** {@code options}: when present (1..50) the product sells by option and the top-level price/stock/originalPrice are ignored. */
    public record NewProductRequest(String name, String description, Integer price, String imageUrl, List<String> imageUrls,
                                    Integer stock, String category, Integer originalPrice, List<String> hashtags,
                                    List<NewOptionRequest> options) {
    }

    public record IdResponse(String id) {
    }
}
