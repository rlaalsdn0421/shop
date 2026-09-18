package com.shop.backend.infrastructure;

import com.shop.backend.domain.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads a Bearer JWT and, if valid, authenticates the request as the token's user (id as
 * principal, ROLE_&lt;role&gt; as the sole granted authority). An invalid/expired/missing token
 * is never an error here — it just leaves the request unauthenticated so the authorization
 * rules in SecurityConfig can produce a clean 401/403 instead of a raw parsing exception.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            try {
                Claims claims = jwtService.parseToken(header.substring(BEARER_PREFIX.length()));
                String userId = jwtService.extractUserId(claims);
                Role role = jwtService.extractRole(claims);
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ex) {
                // Invalid/expired token: leave SecurityContext unauthenticated, let it fall through to a 401.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
