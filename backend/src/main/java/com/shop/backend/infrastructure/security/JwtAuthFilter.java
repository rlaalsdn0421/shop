package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.infrastructure.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads a Bearer JWT and, if valid, authenticates the request as the token's user (id as
 * principal, ROLE_&lt;role&gt; as the sole granted authority). An invalid/expired/missing token
 * is never an error here — it just leaves the request unauthenticated so the authorization
 * rules in SecurityConfig can produce a clean 401/403 instead of a raw parsing exception.
 * A valid token of a deleted (withdrawn) or unknown user is treated the same way.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String userId = null;
            Role role = null;
            try {
                Claims claims = jwtService.parseToken(header.substring(BEARER_PREFIX.length()));
                role = jwtService.extractRole(claims); // throws on a bad role claim, so before userId is set
                userId = jwtService.extractUserId(claims); // non-null userId therefore implies a non-null role
            } catch (Exception ex) {
                // Invalid/expired token: leave SecurityContext unauthenticated, let it fall through to a 401.
                SecurityContextHolder.clearContext();
            }
            // Only a valid token reaches the DB (one PK probe).
            if (userId != null) {
                boolean active;
                try {
                    active = userRepository.existsByIdAndDeletedAtIsNull(userId);
                } catch (DataAccessException ex) {
                    // 503, not 401: a 401 would log a valid user out of the client during a DB outage.
                    logger.error("Auth user lookup failed: " + ex.getClass().getSimpleName() + " sqlState="
                            + sqlState(ex));
                    SecurityConfig.writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "잠시 후 다시 시도해주세요.");
                    return;
                }
                if (active) {
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                    var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        filterChain.doFilter(request, response);
    }

    // Only the SQLSTATE is logged: driver messages carry SQL and row details.
    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
