package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.RateLimiter;
import com.shop.backend.application.service.UserService;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.presentation.dto.UserDtos;
import com.shop.backend.presentation.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public UserController(UserService userService, RateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.userService = userService;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@RequestBody UserDtos.WithdrawRequest request, Authentication authentication,
                         HttpServletRequest httpRequest) {
        // The password check is the same oracle as login, so it shares the login rate limit and counting policy.
        var attempt = rateLimiter.acquireLogin(clientIpResolver.resolve(httpRequest));
        try {
            userService.withdraw(authentication.getName(), request.password());
        } catch (InvalidCredentialsException ex) {
            attempt.countAndRelease();
            throw ex;
        } finally {
            attempt.release(); // success or other error; no-op after countAndRelease
        }
    }
}
