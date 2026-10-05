package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.util.regex.Pattern;

/** Business-rule validation for user registration. */
public final class UserValidation {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    // Lowercase only, so "Bob" and "bob" can never become two different accounts.
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{4,20}$");

    // Case-insensitive policy: any ASCII letter counts. Special = ASCII printable punctuation/symbols only
    // (no whitespace, no letters/digits, no non-ASCII such as Korean).
    private static final Pattern PW_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern PW_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern PW_SPECIAL = Pattern.compile("[!-/:-@\\[-`{-~]");

    private UserValidation() {
    }

    public static void validateRegistration(String username, String email, String password) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new ValidationException("아이디는 영문 소문자, 숫자, 밑줄(_)로 4~20자여야 합니다.");
        }
        if (email == null || email.length() > 200 || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ValidationException("이메일 형식이 올바르지 않습니다.");
        }
        if (password == null || password.length() < 8) {
            throw new ValidationException("비밀번호는 8자 이상이어야 합니다.");
        }
        if (password.length() > 100) {
            throw new ValidationException("비밀번호는 100자를 초과할 수 없습니다.");
        }
        if (password.isBlank()) {
            throw new ValidationException("비밀번호는 공백만으로 만들 수 없습니다.");
        }
        if (!PW_LETTER.matcher(password).find()
                || !PW_DIGIT.matcher(password).find()
                || !PW_SPECIAL.matcher(password).find()) {
            throw new ValidationException("비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.");
        }
    }
}
