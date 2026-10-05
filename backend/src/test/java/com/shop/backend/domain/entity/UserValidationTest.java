package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserValidationTest {

    private static final String USERNAME_MSG = "아이디는 영문 소문자, 숫자, 밑줄(_)로 4~20자여야 합니다.";
    private static final String EMAIL_MSG = "이메일 형식이 올바르지 않습니다.";
    private static final String PW_SHORT_MSG = "비밀번호는 8자 이상이어야 합니다.";
    private static final String PW_LONG_MSG = "비밀번호는 100자를 초과할 수 없습니다.";
    private static final String PW_BLANK_MSG = "비밀번호는 공백만으로 만들 수 없습니다.";

    private static final String OK_USER = "user_01";
    private static final String OK_EMAIL = "user01@example.com";
    private static final String OK_PW = "password-1";

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "abcdefghij0123456789", "a_1_", "user_01"})
    void 성공_허용되는_아이디는_통과한다(String username) {
        assertThatCode(() -> UserValidation.validateRegistration(username, OK_EMAIL, OK_PW))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "abcdefghij01234567890", "Abcd", "ab cd", "ab-cd"})
    void 실패_허용되지_않는_아이디는_아이디_메시지로_거절한다(String username) {
        assertThatThrownBy(() -> UserValidation.validateRegistration(username, OK_EMAIL, OK_PW))
                .isInstanceOf(ValidationException.class)
                .hasMessage(USERNAME_MSG);
    }

    @Test
    void 성공_이메일이_정확히_200자면_통과한다() {
        String email = "a".repeat(200 - "@b.com".length()) + "@b.com";
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, email, OK_PW))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no-at", "a@b", "a b@c.com", "@b.com", "a@@b.com"})
    void 실패_형식이_잘못된_이메일은_이메일_메시지로_거절한다(String email) {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, email, OK_PW))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_MSG);
    }

    @Test
    void 실패_이메일이_200자를_넘으면_거절한다() {
        String email = "a".repeat(201 - "@b.com".length()) + "@b.com";
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, email, OK_PW))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_MSG);
    }

    @Test
    void 성공_비밀번호가_8자와_100자면_통과한다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "a".repeat(8)))
                .doesNotThrowAnyException();
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "a".repeat(100)))
                .doesNotThrowAnyException();
    }

    @Test
    void 실패_비밀번호가_null이거나_7자면_짧다는_메시지로_거절한다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, null))
                .isInstanceOf(ValidationException.class).hasMessage(PW_SHORT_MSG);
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "a".repeat(7)))
                .isInstanceOf(ValidationException.class).hasMessage(PW_SHORT_MSG);
    }

    @Test
    void 실패_비밀번호가_101자면_길다는_메시지로_거절한다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "a".repeat(101)))
                .isInstanceOf(ValidationException.class).hasMessage(PW_LONG_MSG);
    }

    @Test
    void 실패_공백만으로_된_8자_비밀번호는_공백_메시지로_거절한다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, " ".repeat(8)))
                .isInstanceOf(ValidationException.class).hasMessage(PW_BLANK_MSG);
    }

    @Test
    void 실패_공백만으로_된_7자_비밀번호는_짧다는_메시지가_먼저다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, " ".repeat(7)))
                .isInstanceOf(ValidationException.class).hasMessage(PW_SHORT_MSG);
    }

    @Test
    void 성공_공백이_섞여_있어도_다른_문자가_있으면_통과한다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "pass word 1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "       a"))
                .doesNotThrowAnyException();
    }

    @Test
    void 실패_아이디와_이메일이_모두_틀리면_아이디_메시지가_먼저다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration("Bob", "no-at", "short"))
                .isInstanceOf(ValidationException.class)
                .hasMessage(USERNAME_MSG);
    }

    @Test
    void 실패_이메일과_비밀번호가_모두_틀리면_이메일_메시지가_먼저다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, "no-at", "short"))
                .isInstanceOf(ValidationException.class)
                .hasMessage(EMAIL_MSG);
    }

    @Test
    void 성공_모두_올바르면_통과한다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, OK_PW))
                .doesNotThrowAnyException();
    }
}
