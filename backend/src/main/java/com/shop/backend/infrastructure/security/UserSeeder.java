package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the two fixed, non-self-registerable accounts (ADMIN, SELLER) on startup.
 * Email and password both come from the environment; an account is skipped unless both are set,
 * so nothing is seeded (and no credential lives in the repo) by default.
 */
@Component
public class UserSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String sellerEmail;
    private final String sellerPassword;

    public UserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${seed.admin-email}") String adminEmail,
            @Value("${seed.admin-password}") String adminPassword,
            @Value("${seed.seller-email}") String sellerEmail,
            @Value("${seed.seller-password}") String sellerPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.sellerEmail = sellerEmail;
        this.sellerPassword = sellerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminEmail.isBlank() && adminEmail.equalsIgnoreCase(sellerEmail)) {
            throw new IllegalStateException("ADMIN_EMAIL and SELLER_EMAIL must be different");
        }
        seed(adminEmail, adminPassword, Role.ADMIN);
        seed(sellerEmail, sellerPassword, Role.SELLER);
    }

    private void seed(String email, String password, Role role) {
        if (email.isBlank() || password.isBlank()) {
            logger.info("Skipping {} seed: email/password not configured", role);
            return;
        }
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(password), role));
        logger.info("Seeded {} account", role);
    }
}
