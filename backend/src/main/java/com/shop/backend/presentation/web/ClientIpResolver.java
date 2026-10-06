package com.shop.backend.presentation.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client IP from X-Forwarded-For, taking the entry {@code trustedProxyHops} positions from the right:
 * the proxy in front of us (Cloud Run) appends the real client IP, while entries further left are
 * client-supplied and spoofable, so the leftmost entry is never trusted. Falls back to the socket address.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_IP_LENGTH = 64; // auth_attempts.ip column size

    private final int trustedProxyHops;

    public ClientIpResolver(@Value("${rate-limit.trusted-proxy-hops}") int trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    public String resolve(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For");
        if (header != null && !header.isBlank() && trustedProxyHops >= 1) {
            String[] entries = header.split(",", -1); // -1 keeps trailing empty entries so positions stay intact
            if (entries.length >= trustedProxyHops) {
                String ip = entries[entries.length - trustedProxyHops].trim();
                if (!ip.isEmpty() && ip.length() <= MAX_IP_LENGTH) {
                    return ip;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
