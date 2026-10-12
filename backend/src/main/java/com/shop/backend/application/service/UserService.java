package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.domain.error.WithdrawalNotAllowedException;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class UserService {

    static final String WITHDRAWN_REVIEWER_NAME = "탈퇴한 회원";

    private final UserRepository userRepository;
    private final ReviewRepository reviewRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserService(UserRepository userRepository, ReviewRepository reviewRepository,
                       PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.reviewRepository = reviewRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * Anonymizes the account (orders must be kept for 5 years, so the row stays) and masks the reviewer name on
     * its reviews. Re-authenticates with the password: a wrong one is the same failure as a bad login.
     */
    @Transactional
    public void withdraw(String userId, String password) {
        if (password == null || password.isBlank()) {
            throw new ValidationException("비밀번호를 입력해주세요.");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted()) {
            throw new InvalidCredentialsException();
        }
        // Before the password check, so this endpoint is never a password oracle for the seeded ADMIN/SELLER accounts.
        if (user.getRole() != Role.USER) {
            throw new WithdrawalNotAllowedException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.withdraw(clock.instant());
        userRepository.saveAndFlush(user);
        reviewRepository.renameReviewer(userId, WITHDRAWN_REVIEWER_NAME);
    }
}
