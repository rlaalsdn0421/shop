package com.shop.backend.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** The few beans the imported services need; SecurityConfig/ClockConfig are not part of a JPA slice. */
@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfig {

    /** "Now" of every integration test that goes through a service (best-products window math). */
    static final Instant FIXED_NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Bean
    Clock clock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(4); // low cost: hashing speed is irrelevant here
    }
}
