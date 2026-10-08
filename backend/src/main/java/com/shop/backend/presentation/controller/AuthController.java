package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.presentation.dto.AuthDtos;
import com.shop.backend.presentation.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService, RateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/register")
    public AuthDtos.RegisterResponse register(@RequestBody AuthDtos.RegisterRequest request,
                                              HttpServletRequest httpRequest) {
        var attempt = rateLimiter.acquireRegister(clientIpResolver.resolve(httpRequest));
        try {
            var user = authService.register(request.username(), request.email(), request.password(),
                    request.birthDate());
            return AuthDtos.RegisterResponse.from(user);
        } finally {
            // every register request counts, whatever its outcome (success, 400, unexpected error)
            attempt.countAndRelease();
        }
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@RequestBody AuthDtos.LoginRequest request,
                                        HttpServletRequest httpRequest) {
        var attempt = rateLimiter.acquireLogin(clientIpResolver.resolve(httpRequest));
        try {
            var result = authService.login(request.username(), request.password());
            return AuthDtos.LoginResponse.from(result);
        } catch (InvalidCredentialsException ex) {
            attempt.countAndRelease();
            throw ex;
        } finally {
            attempt.release(); // success or unexpected error; no-op after countAndRelease
        }
    }
}
