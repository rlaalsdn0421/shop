package com.shop.backend.application.service;

import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.DuplicateUsernameException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.entity.UserValidation;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Compared against when the username is unknown so unknown-user and wrong-password take similar time.
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @Transactional
    public User register(String username, String email, String password) {
        UserValidation.validateRegistration(username, email, password);
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateUsernameException();
        }
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        User user = new User(username, email, passwordEncoder.encode(password), Role.USER);
        try {
            // flush so a concurrent duplicate hits the unique constraint here, not at commit
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            String cause = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (cause.contains("uq_users_username")) {
                throw new DuplicateUsernameException();
            }
            if (cause.contains("uq_users_email")) {
                throw new DuplicateEmailException();
            }
            throw ex;
        }
    }

    public record LoginResult(String token, String role, String username) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new InvalidCredentialsException();
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new InvalidCredentialsException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        String token = jwtService.generateToken(user);
        return new LoginResult(token, user.getRole().name(), user.getUsername());
    }
}
