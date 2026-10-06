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
        rateLimiter.checkAndRecordRegister(clientIpResolver.resolve(httpRequest));
        var user = authService.register(request.username(), request.email(), request.password());
        return AuthDtos.RegisterResponse.from(user);
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@RequestBody AuthDtos.LoginRequest request,
                                        HttpServletRequest httpRequest) {
        String ip = clientIpResolver.resolve(httpRequest);
        rateLimiter.assertLoginAllowed(ip);
        try {
            var result = authService.login(request.username(), request.password());
            return AuthDtos.LoginResponse.from(result);
        } catch (InvalidCredentialsException ex) {
            rateLimiter.recordLoginFailure(ip);
            throw ex;
        }
    }
}
