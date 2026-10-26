package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.WishlistItem;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.repository.WishlistRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WishlistService {

    static final int MAX_WISHLIST = 200;
    private static final Pageable CAP = PageRequest.of(0, MAX_WISHLIST);

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final EntityManager em;

    public WishlistService(WishlistRepository wishlistRepository, ProductRepository productRepository,
                           UserRepository userRepository, EntityManager em) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<String> ids(String userId) {
        return wishlistRepository.findProductIdsByUser(userId, CAP);
    }

    /** Newest wish first. */
    @Transactional(readOnly = true)
    public List<Product> list(String userId) {
        return wishlistRepository.findWishedProductsByUser(userId, CAP);
    }

    /**
     * Idempotent. The user row is locked until commit: a concurrent withdrawal (row UPDATE) either commits first
     * (rejected here) or waits for this add and then deletes its row, and a user's adds are serialized so the count
     * check is exact.
     */
    @Transactional
    public void add(String userId, String productId) {
        // Transaction-scoped (same as ReviewService): a stuck withdrawal fails this add after 3 s (-> 503).
        em.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
        userRepository.findForUpdateById(userId).filter(user -> !user.isDeleted())
                .orElseThrow(InvalidCredentialsException::new);
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        if (wishlistRepository.existsById(new WishlistItem.WishlistItemId(userId, productId))) {
            return;
        }
        if (wishlistRepository.countByUser(userId) >= MAX_WISHLIST) {
            throw new ValidationException("찜은 최대 200개까지 담을 수 있어요.");
        }
        wishlistRepository.insertIfAbsent(userId, productId);
    }

    @Transactional
    public void remove(String userId, String productId) {
        wishlistRepository.deleteByUserAndProduct(userId, productId);
    }
}
