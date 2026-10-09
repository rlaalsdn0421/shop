package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, String> {

    // Only paid orders count as sales.
    String SOLD = "(SELECT COALESCE(SUM(oi.quantity), 0) FROM OrderItem oi WHERE oi.product = p AND oi.order.status = '" + Order.STATUS_PAID + "')";
    String REVIEW_COUNT = "(SELECT COUNT(r) FROM Review r WHERE r.product = p)";
    String AVG_RATING = "(SELECT AVG(r.rating) FROM Review r WHERE r.product = p)";
    String CATEGORY_FILTER = "WHERE (:category IS NULL OR p.category = :category) ";
    // Every ordering below ends with createdAt, id: equal keys would otherwise repeat/skip rows across pages.
    String NEWEST_TIE = ", p.createdAt DESC, p.id DESC";
    String POPULAR_QUERY = "SELECT p FROM Product p " + CATEGORY_FILTER
            + "ORDER BY (" + SOLD + " + " + REVIEW_COUNT + ") DESC" + NEWEST_TIE;

    List<Product> findAllByOrderByCreatedAtDesc();

    // createdAt alone isn't unique (seed rows share the same timestamp), so ties are
    // broken by id — otherwise consecutive pages can return overlapping/duplicate rows.
    Page<Product> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<Product> findAllByCategoryOrderByCreatedAtDescIdDesc(String category, Pageable pageable);

    // Sort comes from the Pageable (price sorts); the service always appends createdAt, id.
    Page<Product> findAllByCategory(String category, Pageable pageable);

    List<Product> findAllByIdIn(List<String> ids);

    // category == null means all categories. Pageable must be unsorted: the order is in the query.
    @Query("SELECT p FROM Product p " + CATEGORY_FILTER + "ORDER BY " + REVIEW_COUNT + " DESC" + NEWEST_TIE)
    Page<Product> findByReviewCountDesc(@Param("category") String category, Pageable pageable);

    // Products without reviews have a NULL average and go last; ties -> more reviews first.
    @Query("SELECT p FROM Product p " + CATEGORY_FILTER
            + "ORDER BY " + AVG_RATING + " DESC NULLS LAST, " + REVIEW_COUNT + " DESC" + NEWEST_TIE)
    Page<Product> findByRatingDesc(@Param("category") String category, Pageable pageable);

    @Query("SELECT p FROM Product p " + CATEGORY_FILTER + "ORDER BY " + SOLD + " DESC" + NEWEST_TIE)
    Page<Product> findBySalesDesc(@Param("category") String category, Pageable pageable);

    @Query(POPULAR_QUERY)
    Page<Product> findByPopularDesc(@Param("category") String category, Pageable pageable);

    // Same ordering without the COUNT query, for callers that only need the first rows (best-products top-up).
    @Query(POPULAR_QUERY)
    List<Product> listByPopularDesc(@Param("category") String category, Pageable pageable);

    // Best products: only products sold since :since, most units first, ties -> overall popularity.
    @Query("SELECT p FROM OrderItem oi JOIN oi.product p WHERE oi.order.status = '" + Order.STATUS_PAID + "' AND oi.order.createdAt >= :since "
            + "GROUP BY p ORDER BY SUM(oi.quantity) DESC, (" + SOLD + " + " + REVIEW_COUNT + ") DESC" + NEWEST_TIE)
    List<Product> findBestSoldSince(@Param("since") Instant since, Pageable pageable);
}
