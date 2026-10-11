package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Optional<Order> findByIdAndUserId(String id, String userId);

    // Keyset pagination (never OFFSET): the caller passes a Pageable of size+1 to learn whether another page exists.
    @Query("SELECT o FROM Order o WHERE o.userId = :userId ORDER BY o.createdAt DESC, o.id DESC")
    List<Order> findFirstPageByUser(@Param("userId") String userId, Pageable pageable);

    // Row comparison (created_at, id) < (?, ?): one range condition PostgreSQL can walk on (user_id, created_at DESC, id DESC).
    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND (o.createdAt, o.id) < (:createdAt, :id) "
            + "ORDER BY o.createdAt DESC, o.id DESC")
    List<Order> findPageByUserAfter(@Param("userId") String userId, @Param("createdAt") Instant createdAt,
                                    @Param("id") String id, Pageable pageable);

    // One query for the whole page's lines and their products (no N+1); the order id is a plain column, no join needed.
    @Query("SELECT i FROM OrderItem i JOIN FETCH i.product WHERE i.order.id IN :orderIds")
    List<OrderItem> findItemsWithProductByOrderIds(@Param("orderIds") Collection<String> orderIds);

    /**
     * Blocks until no other open transaction holds the same user+key lock; released at commit/rollback. A repository
     * method (not em.createNativeQuery) so a lock_timeout failure is translated to PessimisticLockingFailureException
     * (-> 503) instead of leaking as a raw jakarta.persistence exception (-> 500). The CAST is needed because
     * Hibernate cannot read the void result.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtextextended(CAST(:userId AS text) || ':' || CAST(:key AS text), 0)) AS text)",
            nativeQuery = true)
    String lockUserIdempotencyKey(@Param("userId") String userId, @Param("key") String key);
}
