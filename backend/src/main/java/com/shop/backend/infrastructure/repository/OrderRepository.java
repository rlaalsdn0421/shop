package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;

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

    // FOR UPDATE: the payment confirmation and the expiry job serialize on the order row (use inside a transaction).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    // FOR UPDATE SKIP LOCKED (Hibernate lock timeout -2): empty when another transaction holds the row or it is gone.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdateSkipLocked(@Param("id") String id);

    // Candidates for the expiry job, oldest deadline first (served by idx_orders_pending_expires); re-checked under the lock.
    @Query("SELECT o.id FROM Order o WHERE o.status = '" + Order.STATUS_PENDING_PAYMENT
            + "' AND o.expiresAt < :now ORDER BY o.expiresAt")
    List<String> findExpiredPendingIds(@Param("now") Instant now, Pageable pageable);

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
