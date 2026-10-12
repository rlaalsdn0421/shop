package com.shop.backend.infrastructure.repository;

import com.shop.backend.domain.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** Single PK probe for the auth filter: true only for an existing, non-withdrawn account. */
    boolean existsByIdAndDeletedAtIsNull(String id);

    /**
     * FOR SHARE on the user row: blocks while a withdrawal (row UPDATE) is in flight and, once that commits, returns
     * the fresh row, so the caller sees deleted_at. Concurrent sharers do not block each other. Through the
     * repository so a lock_timeout failure is translated to PessimisticLockingFailureException (-> 503).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<User> findForShareById(String id);
}
