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
 * Username and password both come from the environment; an account is skipped unless both are set,
 * so nothing is seeded (and no credential lives in the repo) by default. These accounts have no email.
 */
@Component
public class UserSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;
    private final String sellerUsername;
    private final String sellerPassword;

    public UserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${seed.admin-username}") String adminUsername,
            @Value("${seed.admin-password}") String adminPassword,
            @Value("${seed.seller-username}") String sellerUsername,
            @Value("${seed.seller-password}") String sellerPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.sellerUsername = sellerUsername;
        this.sellerPassword = sellerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!adminUsername.isBlank() && adminUsername.equalsIgnoreCase(sellerUsername)) {
            throw new IllegalStateException("ADMIN_USERNAME and SELLER_USERNAME must be different");
        }
        seed(adminUsername, adminPassword, Role.ADMIN);
        seed(sellerUsername, sellerPassword, Role.SELLER);
    }

    private void seed(String username, String password, Role role) {
        if (username.isBlank() || password.isBlank()) {
            logger.info("Skipping {} seed: username/password not configured", role);
            return;
        }
        if (userRepository.existsByUsername(username)) {
            return;
        }
        userRepository.save(new User(username, null, passwordEncoder.encode(password), role));
        logger.info("Seeded {} account", role);
    }
}
