package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    Optional<Order> findByIdAndUserId(String id, String userId);

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
