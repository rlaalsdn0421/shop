package com.shop.backend.application.service;

import com.shop.backend.domain.error.DuplicateEmailException;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.entity.UserValidation;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public User register(String email, String password) {
        UserValidation.validateRegistration(email, password);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        User user = new User(email, passwordEncoder.encode(password), Role.USER);
        return userRepository.save(user);
    }

    public record LoginResult(String token, String role, String email) {
    }

    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        User user = userRepository.findByEmail(email).orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        String token = jwtService.generateToken(user);
        return new LoginResult(token, user.getRole().name(), user.getEmail());
    }
}
