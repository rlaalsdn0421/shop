package com.shop.backend.presentation.dto;

/** Request shapes for the /api/users endpoints. */
public final class UserDtos {

    private UserDtos() {
    }

    public record WithdrawRequest(String password) {
    }
}
