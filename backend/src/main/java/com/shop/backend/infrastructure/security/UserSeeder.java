package com.shop.backend.infrastructure.security;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Seeds the two fixed, non-self-registerable accounts (ADMIN, SELLER) on startup. */
@Component
public class UserSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;
    private final String sellerPassword;

    public UserSeeder(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${seed.admin-password}") String adminPassword,
            @Value("${seed.seller-password}") String sellerPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
        this.sellerPassword = sellerPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("admin@shop.local", adminPassword, Role.ADMIN);
        seed("seller@shop.local", sellerPassword, Role.SELLER);
    }

    private void seed(String email, String password, Role role) {
        if (userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(email, passwordEncoder.encode(password), role));
        logger.info("Seeded {} account ({})", role, email);
    }
}
