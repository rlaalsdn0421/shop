package com.shop.backend.presentation;

import com.shop.backend.application.AuthService;
import com.shop.backend.domain.User;

/** Request/response shapes for the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(String email, String password) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RegisterResponse(String id) {
        static RegisterResponse from(User user) {
            return new RegisterResponse(user.getId());
        }
    }

    public record LoginResponse(String token, String role, String email) {
        static LoginResponse from(AuthService.LoginResult result) {
            return new LoginResponse(result.token(), result.role(), result.email());
        }
    }
}
