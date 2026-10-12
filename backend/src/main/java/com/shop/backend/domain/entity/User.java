package com.shop.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, length = 200, unique = true)
    private String username;

    /** Contact address for regular members; ADMIN/SELLER accounts have none. */
    @Column(length = 200, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Personal data: never expose in responses or logs. Null for seeded ADMIN/SELLER and pre-existing accounts. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set on withdrawal. The row is kept (orders must be retained) but anonymized and can no longer log in. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected User() {
        // JPA
    }

    public User(String username, String email, String passwordHash, Role role) {
        this(username, email, passwordHash, role, null);
    }

    public User(String username, String email, String passwordHash, Role role, LocalDate birthDate) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.birthDate = birthDate;
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Anonymizes this account; the placeholder hash is not a BCrypt hash, so no password can ever match it. */
    public void withdraw(Instant now) {
        this.username = "deleted-" + id;
        this.email = "deleted-" + id + "@" + UserValidation.WITHDRAWN_EMAIL_DOMAIN;
        this.passwordHash = "!";
        this.birthDate = null;
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
