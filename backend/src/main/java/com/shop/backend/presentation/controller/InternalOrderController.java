package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.PaymentService;
import com.shop.backend.presentation.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Called by Cloud Scheduler with a shared token (no JWT). Disabled (404) while INTERNAL_TOKEN is empty or shorter than 32 characters. */
@RestController
public class InternalOrderController {

    static final int MIN_TOKEN_LENGTH = 32;
    private static final Logger logger = LoggerFactory.getLogger(InternalOrderController.class);

    private final PaymentService paymentService;
    private final String token;

    public InternalOrderController(PaymentService paymentService, @Value("${internal.token:}") String token) {
        this.paymentService = paymentService;
        boolean set = token != null && !token.isBlank();
        if (set && token.length() < MIN_TOKEN_LENGTH) {
            // never print the token itself
            logger.warn("internal.token is shorter than {} characters: /api/internal/** is disabled", MIN_TOKEN_LENGTH);
        }
        this.token = set && token.length() >= MIN_TOKEN_LENGTH ? token : null; // null = disabled (404)
    }

    @PostMapping("/api/internal/orders/expire-pending")
    public ResponseEntity<?> expirePending(@RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        if (token == null) {
            return ResponseEntity.notFound().build();
        }
        if (provided == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("인증이 필요합니다."));
        }
        return ResponseEntity.ok(Map.of("expired", paymentService.expirePending()));
    }
}
