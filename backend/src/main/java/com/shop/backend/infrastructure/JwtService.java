package com.shop.backend.infrastructure;

import com.shop.backend.domain.Role;
import com.shop.backend.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/** Encapsulates JWT signing/parsing for stateless auth. */
@Component
public class JwtService {

    private static final long EXPIRY_SECONDS = 2 * 60 * 60; // 2 hours

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(EXPIRY_SECONDS)))
                .signWith(key)
                .compact();
    }

    /** Throws an unchecked JJWT exception (expired/invalid signature/malformed) on failure. */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    public String extractEmail(Claims claims) {
        return claims.get("email", String.class);
    }

    public Role extractRole(Claims claims) {
        return Role.valueOf(claims.get("role", String.class));
    }
}
