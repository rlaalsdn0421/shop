package com.shop.backend.application.service;

import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductImage;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.entity.ProductValidation;
import com.shop.backend.infrastructure.repository.ProductImageRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final Clock clock;

    public ProductService(ProductRepository productRepository, ProductImageRepository productImageRepository, Clock clock) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.clock = clock;
    }

    /**
     * A non-empty {@code imageUrls} wins over the legacy {@code imageUrl}; its first entry becomes products.image_url
     * (the representative image) and every entry gets a product_images row. Legacy imageUrl alone creates no rows.
     */
    @Transactional
    public Product createProduct(String name, String description, Integer price, String imageUrl, List<String> imageUrls,
                                 Integer stock, String category, Integer originalPrice, List<String> hashtags) {
        String normalizedCategory = category == null || category.trim().isEmpty() ? null : category.trim();
        boolean gallery = imageUrls != null && !imageUrls.isEmpty();
        List<String> urls = gallery ? ProductValidation.normalizeImageUrls(imageUrls) : List.of();
        String representative = gallery ? urls.get(0) : imageUrl;
        ProductValidation.validateNewProduct(name, description, price, representative, stock, normalizedCategory, originalPrice);
        List<String> normalizedTags = ProductValidation.normalizeHashtags(hashtags);
        Product saved = productRepository.save(new Product(name, description, price, representative, stock, normalizedCategory, originalPrice, normalizedTags));
        if (gallery) {
            List<ProductImage> rows = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                rows.add(new ProductImage(saved.getId(), urls.get(i), i));
            }
            productImageRepository.saveAll(rows);
        }
        return saved;
    }

    /** Gallery of one product in display order; products without product_images rows (old/legacy) show just their image_url. */
    @Transactional(readOnly = true)
    public List<String> getImageUrls(Product product) {
        List<String> urls = productImageRepository.findByProductIdOrderBySortOrderAsc(product.getId()).stream()
                .map(ProductImage::getUrl).toList();
        return urls.isEmpty() ? List.of(product.getImageUrl()) : urls;
    }

    @Transactional(readOnly = true)
    public Optional<Product> getProduct(String id) {
        return productRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Page<Product> listProducts(String category, ProductSort sort, int page, int size) {
        String trimmed = category == null || category.isBlank() ? null : category.trim();
        PageRequest unsorted = PageRequest.of(page, size);
        return switch (sort) {
            case NEWEST -> trimmed == null
                    ? productRepository.findAllByOrderByCreatedAtDescIdDesc(unsorted)
                    : productRepository.findAllByCategoryOrderByCreatedAtDescIdDesc(trimmed, unsorted);
            case PRICE_ASC -> byPrice(trimmed, Sort.Direction.ASC, page, size);
            case PRICE_DESC -> byPrice(trimmed, Sort.Direction.DESC, page, size);
            case REVIEWS -> productRepository.findByReviewCountDesc(trimmed, unsorted);
            case RATING -> productRepository.findByRatingDesc(trimmed, unsorted);
            case SALES -> productRepository.findBySalesDesc(trimmed, unsorted);
            case POPULAR -> productRepository.findByPopularDesc(trimmed, unsorted);
            case DISCOUNT -> productRepository.findByDiscountDesc(trimmed, unsorted);
        };
    }

    private Page<Product> byPrice(String category, Sort.Direction direction, int page, int size) {
        // createdAt, id break price ties so pages never overlap
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(direction, "price").and(Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return category == null
                ? productRepository.findAll(pageRequest)
                : productRepository.findAllByCategory(category, pageRequest);
    }

    /** Best sellers of the period; if too few products sold, the rest is topped up with the overall popular ones. */
    @Transactional(readOnly = true)
    public List<Product> listBestProducts(BestPeriod period, int size) {
        Instant since = clock.instant().minus(period.window());
        List<Product> best = new ArrayList<>(productRepository.findBestSoldSince(since, PageRequest.of(0, size)));
        if (best.size() >= size) {
            return best;
        }
        Set<String> taken = best.stream().map(Product::getId).collect(Collectors.toSet());
        // fetch size + already-taken so that skipping duplicates still leaves enough fillers
        for (Product p : productRepository.listByPopularDesc(null, PageRequest.of(0, size + best.size()))) {
            if (best.size() >= size) {
                break;
            }
            if (taken.add(p.getId())) {
                best.add(p);
            }
        }
        return best;
    }

    @Transactional(readOnly = true)
    public List<Product> listAdminProducts() {
        return productRepository.findAllByOrderByCreatedAtDesc();
    }
}
