package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.WishlistService;
import com.shop.backend.presentation.dto.ProductDtos;
import com.shop.backend.presentation.dto.WishlistDtos;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The caller's own wishlist; the user id comes only from the JWT principal, SecurityConfig requires authentication. */
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {

    private final WishlistService wishlistService;

    public WishlistController(WishlistService wishlistService) {
        this.wishlistService = wishlistService;
    }

    @GetMapping("/ids")
    public ResponseEntity<WishlistDtos.WishlistIds> ids(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new WishlistDtos.WishlistIds(wishlistService.ids(authentication.getName())));
    }

    @GetMapping
    public ResponseEntity<List<ProductDtos.ProductListItem>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ProductDtos.ProductListItem.from(wishlistService.list(authentication.getName())));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Void> add(@PathVariable String productId, Authentication authentication) {
        wishlistService.add(authentication.getName(), productId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> remove(@PathVariable String productId, Authentication authentication) {
        wishlistService.remove(authentication.getName(), productId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
