package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.presentation.dto.AuthDtos;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthDtos.RegisterResponse register(@RequestBody AuthDtos.RegisterRequest request) {
        var user = authService.register(request.username(), request.email(), request.password());
        return AuthDtos.RegisterResponse.from(user);
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@RequestBody AuthDtos.LoginRequest request) {
        var result = authService.login(request.username(), request.password());
        return AuthDtos.LoginResponse.from(result);
    }
}
