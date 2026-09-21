package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.util.regex.Pattern;

/** Business-rule validation for user registration. */
public final class UserValidation {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private UserValidation() {
    }

    public static void validateRegistration(String email, String password) {
        if (email == null || email.length() > 200 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ValidationException("이메일 형식이 올바르지 않습니다.");
        }
        if (password == null || password.length() < 8) {
            throw new ValidationException("비밀번호는 8자 이상이어야 합니다.");
        }
        if (password.length() > 100) {
            throw new ValidationException("비밀번호는 100자를 초과할 수 없습니다.");
        }
    }
}
