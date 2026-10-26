package com.shop.backend.presentation.dto;

import java.util.List;

public final class WishlistDtos {

    private WishlistDtos() {
    }

    public record WishlistIds(List<String> ids) {
    }
}
