package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    private static final int MIN_AGE = 14;

    private UserValidation() {
    }

    /** Requires a real birth date and age 만 14+. "Today" is the Seoul date of the given clock, whatever its zone. */
    public static void validateBirthDate(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            throw new ValidationException("생년월일을 입력해주세요.");
        }
        LocalDate today = LocalDate.now(clock.withZone(KOREA));
        if (birthDate.isAfter(today) || birthDate.isBefore(EARLIEST_BIRTH_DATE)) {
            throw new ValidationException("생년월일이 올바르지 않아요.");
        }
        if (birthDate.isAfter(today.minusYears(MIN_AGE))) {
            throw new ValidationException("만 14세 이상만 가입할 수 있어요.");
        }
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
