package com.shop.backend.presentation.dto;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.domain.entity.User;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;

/** Request/response shapes for the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(String username, String email, String password,
                                  @JsonDeserialize(using = IsoDateDeserializer.class) LocalDate birthDate) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record RegisterResponse(String id) {
        public static RegisterResponse from(User user) {
            return new RegisterResponse(user.getId());
        }
    }

    public record LoginResponse(String token, String role, String username) {
        public static LoginResponse from(AuthService.LoginResult result) {
            return new LoginResponse(result.token(), result.role(), result.username());
        }
    }
}
