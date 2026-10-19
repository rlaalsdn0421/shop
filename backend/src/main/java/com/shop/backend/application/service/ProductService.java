package com.shop.backend.application.service;

import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.OptionEdit;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductImage;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.entity.ProductValidation;
import com.shop.backend.domain.entity.StockKey;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.StockChangedException;
import com.shop.backend.infrastructure.repository.ProductImageRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductOptionRepository productOptionRepository;
    private final Clock clock;
    private final EntityManager em;

    public ProductService(ProductRepository productRepository, ProductImageRepository productImageRepository,
                          ProductOptionRepository productOptionRepository, Clock clock, EntityManager em) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.productOptionRepository = productOptionRepository;
        this.clock = clock;
        this.em = em;
    }

    /**
     * A non-empty {@code imageUrls} wins over the legacy {@code imageUrl}; its first entry becomes products.image_url
     * (the representative image) and every entry gets a product_images row. Legacy imageUrl alone creates no rows.
     * <p>
     * A non-empty {@code options} list makes a product with options (product_options rows, sort_order = list index):
     * the top-level {@code price}, {@code originalPrice} and {@code stock} are ignored, products.price/original_price
     * are the cheapest option's (the first one when several tie), price_varies is true unless all option prices are
     * equal, and products.stock is 0 (only option stock is sold). Null/empty {@code options} = the unchanged legacy path.
     */
    @Transactional
    public Product createProduct(String name, String description, Integer price, String imageUrl, List<String> imageUrls,
                                 Integer stock, String category, Integer originalPrice, List<String> hashtags,
                                 List<NewProductOption> options) {
        String normalizedCategory = category == null || category.trim().isEmpty() ? null : category.trim();
        boolean gallery = imageUrls != null && !imageUrls.isEmpty();
        List<String> urls = gallery ? ProductValidation.normalizeImageUrls(imageUrls) : List.of();
        String representative = gallery ? urls.get(0) : imageUrl;
        boolean withOptions = options != null && !options.isEmpty();
        List<NewProductOption> cleanOptions = withOptions ? ProductValidation.normalizeOptions(options) : List.of();
        NewProductOption cheapest = cleanOptions.stream().min(Comparator.comparing(NewProductOption::price)).orElse(null);
        if (withOptions) {
            price = cheapest.price();
            originalPrice = cheapest.originalPrice();
            stock = 0;
        }
        ProductValidation.validateNewProduct(name, description, price, representative, stock, normalizedCategory, originalPrice);
        List<String> normalizedTags = ProductValidation.normalizeHashtags(hashtags);
        boolean priceVaries = cleanOptions.stream().anyMatch(o -> !o.price().equals(cheapest.price()));
        Product saved = productRepository.save(new Product(name, description, price, representative, stock, normalizedCategory,
                originalPrice, normalizedTags, priceVaries));
        if (gallery) {
            List<ProductImage> rows = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                rows.add(new ProductImage(saved.getId(), urls.get(i), i));
            }
            productImageRepository.saveAll(rows);
        }
        if (withOptions) {
            List<ProductOption> rows = new ArrayList<>();
            for (int i = 0; i < cleanOptions.size(); i++) {
                rows.add(new ProductOption(saved.getId(), cleanOptions.get(i), i));
            }
            productOptionRepository.saveAll(rows);
        }
        return saved;
    }

    /**
     * Admin edit, full replace of the editable fields (not the option set or names). One transaction: lock the product
     * row, then its option rows one at a time in StockKey order (same order as order creation/cancel/expiry, so no
     * deadlock; never one ORDER BY ... FOR UPDATE, the DB collation may differ from Java's String order). Stock is typed
     * in; an item whose new stock differs from {@code expectedStock} is checked against the LOCKED current value and any
     * mismatch throws {@link StockChangedException} (everything rolls back). An item whose stock the admin did not
     * change keeps its current value, so a sale made meanwhile is not undone. Stock is read and written on the entities
     * only: no @Modifying stock UPDATE may run in this transaction (it would leave stale in-memory stock to be re-saved).
     * Products with options: products.price/original_price = cheapest option (first when tied), price_varies, stock 0.
     */
    @Transactional
    public Product updateProduct(String id, String name, String description, String category, List<String> hashtags,
                                 List<String> imageUrls, Integer price, Integer originalPrice, Integer stock,
                                 Integer expectedStock, List<OptionEdit> options, String actor) {
        // Pure input rules first: a bad body never takes a lock (or waits for one).
        String normalizedCategory = category == null || category.trim().isEmpty() ? null : category.trim();
        List<String> urls = ProductValidation.normalizeImageUrls(imageUrls);
        List<String> normalizedTags = ProductValidation.normalizeHashtags(hashtags);
        ProductValidation.validateProductText(name, description, urls.get(0));
        ProductValidation.validateCategory(normalizedCategory);

        // Transaction-scoped, same as OrderService/PaymentTransactions: a stuck lock holder fails this edit after 3 s (-> 503).
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        // Product row first, then option rows in StockKey order. This ordering is deadlock-free against order creation only
        // because Hibernate emits FOR NO KEY UPDATE for PESSIMISTIC_WRITE on Postgres, which does not conflict with the
        // FOR KEY SHARE an order_items insert takes on the product row; a plain FOR UPDATE would deadlock.
        // ProductEditIntegrationTest pins this.
        Product product = findLocked(Product.class, id);
        if (product == null) {
            throw new ProductNotFoundException(id);
        }
        // ids only: an option entity loaded before its lock would keep stale stock after the lock is taken
        List<String> optionIds = productOptionRepository.findIdsByProductIdOrderBySortOrder(id);
        Map<String, OptionEdit> edits = ProductValidation.validateEditOptions(optionIds, price, originalPrice, stock,
                expectedStock, options);
        int oldPrice = product.getPrice();

        int stockChanges = 0;
        if (optionIds.isEmpty()) {
            ProductValidation.validateNewProduct(name, description, price, urls.get(0), stock, normalizedCategory, originalPrice);
            stockChanges += stock.equals(expectedStock) ? 0 : 1;
            product.applyEdit(name, description, price, urls.get(0), storedStock(product.getStock(), stock, expectedStock),
                    normalizedCategory, originalPrice, normalizedTags, false);
        } else {
            // sort_order order, so "cheapest, first when tied" matches registration
            OptionEdit cheapest = optionIds.stream().map(edits::get).min(Comparator.comparing(OptionEdit::price)).orElseThrow();
            ProductValidation.validateNewProduct(name, description, cheapest.price(), urls.get(0), 0, normalizedCategory,
                    cheapest.originalPrice());
            boolean priceVaries = edits.values().stream().anyMatch(o -> !o.price().equals(cheapest.price()));
            List<String> lockOrder = optionIds.stream().map(oid -> new StockKey(id, oid)).sorted()
                    .map(StockKey::optionId).toList();
            for (String optionId : lockOrder) {
                ProductOption option = findLocked(ProductOption.class, optionId);
                if (option == null) {
                    throw new ProductNotFoundException(id);
                }
                OptionEdit edit = edits.get(optionId);
                stockChanges += edit.stock().equals(edit.expectedStock()) ? 0 : 1;
                option.applyEdit(edit.price(), edit.originalPrice(), storedStock(option.getStock(), edit.stock(), edit.expectedStock()));
            }
            product.applyEdit(name, description, cheapest.price(), urls.get(0), 0, normalizedCategory,
                    cheapest.originalPrice(), normalizedTags, priceVaries);
        }

        // bulk delete runs at once, so the new rows never meet the old ones in UNIQUE (product_id, sort_order)
        productImageRepository.deleteAllByProductId(id);
        List<ProductImage> rows = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            rows.add(new ProductImage(id, urls.get(i), i));
        }
        productImageRepository.saveAll(rows);
        logger.info("Product edited: productId={} actor={} price={}->{} stockChanges={} options={} images={}", id, actor,
                oldPrice, product.getPrice(), stockChanges, optionIds.size(), urls.size());
        return product;
    }

    /**
     * em.find with a row lock. A bare EntityManager call is not translated by Spring (only repositories are), so a lock
     * timeout is converted here to the PessimisticLockingFailureException that GlobalExceptionHandler maps to 503.
     */
    private <T> T findLocked(Class<T> type, String id) {
        try {
            return em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        } catch (PersistenceException e) {
            DataAccessException translated = EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e);
            throw translated != null ? translated : e;
        }
    }

    /** The stock to store: the typed one when it differs from what the admin saw (then that must still be current), else the current one. */
    private static Integer storedStock(Integer current, Integer typed, Integer expected) {
        if (typed.equals(expected)) {
            return current;
        }
        if (!current.equals(expected)) {
            throw new StockChangedException();
        }
        return typed;
    }

    /** Options of one product in display order; empty for products without options. */
    @Transactional(readOnly = true)
    public List<ProductOption> getOptions(Product product) {
        return productOptionRepository.findByProductIdOrderBySortOrderAsc(product.getId());
    }

    /** Admin list stock of products with options: the sum of their option stock, keyed by product id (others are absent). */
    @Transactional(readOnly = true)
    public Map<String, Long> optionStockTotals() {
        return productOptionRepository.sumStockByProduct().stream()
                .collect(Collectors.toMap(ProductOptionRepository.StockTotal::productId, ProductOptionRepository.StockTotal::total));
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
