package com.shop.backend.application.service;

import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Standard Webhooks signature check (what PortOne's SDK verifier does), JDK only. Signed content is
 * "{webhook-id}.{webhook-timestamp}.{raw body}", HMAC-SHA256 with the Base64-decoded secret (after "whsec_").
 * The secret is never logged or put in an exception message.
 */
@Component
public class WebhookSignatureVerifier {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String PREFIX = "whsec_";
    private static final long TOLERANCE_SECONDS = 5 * 60;

    private final String secret;
    private final Clock clock;

    public WebhookSignatureVerifier(@Value("${portone.webhook-secret:}") String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    /** Returns normally only for a valid, fresh signature; ValidationException (400) otherwise. */
    public void verify(String id, String timestamp, String signatureHeader, String body) {
        byte[] key = key();
        if (id == null || timestamp == null || signatureHeader == null || body == null) {
            throw invalid();
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw invalid();
        }
        long skew;
        try {
            skew = Math.subtractExact(clock.instant().getEpochSecond(), seconds); // Math.abs(Long.MIN_VALUE) is negative
        } catch (ArithmeticException e) {
            throw invalid();
        }
        if (skew > TOLERANCE_SECONDS || skew < -TOLERANCE_SECONDS) {
            throw invalid();
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            expected = Base64.getEncoder().encode(
                    mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        }
        boolean match = false;
        for (String candidate : signatureHeader.trim().split("\\s+")) {
            int comma = candidate.indexOf(',');
            if (comma > 0 && "v1".equals(candidate.substring(0, comma))) {
                // no short-circuit: every candidate is compared in constant time
                match |= MessageDigest.isEqual(expected, candidate.substring(comma + 1).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!match) {
            throw invalid();
        }
    }

    private byte[] key() {
        if (secret == null || secret.isBlank()) {
            throw new PaymentNotConfiguredException();
        }
        try {
            return Base64.getDecoder().decode(secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : secret);
        } catch (IllegalArgumentException e) {
            logger.error("portone.webhook-secret is not valid Base64");
            throw new PaymentNotConfiguredException();
        }
    }

    private static ValidationException invalid() {
        return new ValidationException("웹훅 서명이 올바르지 않아요.");
    }
}
