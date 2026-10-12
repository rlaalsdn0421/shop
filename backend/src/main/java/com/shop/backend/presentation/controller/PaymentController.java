package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.PaymentService;
import com.shop.backend.presentation.dto.OrderDtos;
import com.shop.backend.domain.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
public class PaymentController {

    // Real PortOne webhook bodies are a few hundred bytes.
    static final int MAX_WEBHOOK_BYTES = 64 * 1024;

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** The browser says "I paid"; the server only believes what the provider reports (see PaymentService). */
    @PostMapping("/api/orders/{id}/payment/complete")
    public OrderDtos.PaymentCompleteResponse complete(@PathVariable String id, Authentication authentication) {
        return new OrderDtos.PaymentCompleteResponse(paymentService.complete(id, authentication.getName()));
    }

    /**
     * No JWT: authenticated by the Standard Webhooks signature over the RAW body. The three webhook-* headers are
     * checked before the body is read, and the body is read with a hard cap (works for chunked uploads too), so an
     * unauthenticated caller cannot make us buffer or HMAC a huge payload.
     */
    @PostMapping("/api/payments/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "webhook-signature", required = false) String signature,
            HttpServletRequest request) throws IOException {
        if (isBlank(webhookId) || isBlank(timestamp) || isBlank(signature)) {
            throw new ValidationException("웹훅 서명이 올바르지 않아요.");
        }
        if (request.getContentLengthLong() > MAX_WEBHOOK_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_WEBHOOK_BYTES + 1);
        if (body.length > MAX_WEBHOOK_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        paymentService.handleWebhook(webhookId, timestamp, signature, new String(body, StandardCharsets.UTF_8));
        return ResponseEntity.ok().build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
