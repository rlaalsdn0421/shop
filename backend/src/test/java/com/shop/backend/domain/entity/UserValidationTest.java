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
    private static final String PW_COMPOSITION_MSG = "비밀번호는 영문, 숫자, 특수문자를 각각 1자 이상 포함해야 합니다.";

    private static final String OK_USER = "user_01";
    private static final String OK_EMAIL = "user01@example.com";
    private static final String OK_PW = "pass1234!";

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
    void 성공_비밀번호가_정확히_8자면_통과한다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "abcd123!"))
                .doesNotThrowAnyException();
    }

    @Test
    void 성공_비밀번호가_정확히_100자면_통과한다() {
        String pw = "a".repeat(97) + "1!b";
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, pw))
                .doesNotThrowAnyException();
    }

    @Test
    void 성공_영문_대소문자는_구분하지_않는다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "abcdef1!"))
                .doesNotThrowAnyException();
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "ABCDEF1!"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
            ":", ";", "<", "=", ">", "?", "@", "[", "\\", "]", "^", "_", "`", "{", "|", "}", "~"})
    void 성공_ASCII_특수문자는_모두_특수문자로_인정한다(String special) {
        String pw = "abcdef1" + special;
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, pw))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefgh",      // 영문만
            "12345678",      // 숫자만
            "!@#$%^&*",      // 특수문자만
            "abcd1234",      // 영문+숫자 (특수문자 없음)
            "abcdef!@",      // 영문+특수문자 (숫자 없음)
            "1234!@#$",      // 숫자+특수문자 (영문 없음)
            "비밀번호1234!",  // 한글은 영문으로 치지 않음
            "abcd 1234",     // 공백은 특수문자가 아님
            "abcd1234\t",    // 탭도 특수문자가 아님
            "abcd1234한"     // 비ASCII 문자는 특수문자가 아님
    })
    void 실패_영문_숫자_특수문자_중_하나라도_없으면_구성_메시지로_거절한다(String password) {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, password))
                .isInstanceOf(ValidationException.class).hasMessage(PW_COMPOSITION_MSG);
    }

    @Test
    void 실패_비밀번호가_null이거나_7자면_짧다는_메시지로_거절한다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, null))
                .isInstanceOf(ValidationException.class).hasMessage(PW_SHORT_MSG);
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "abc123!"))
                .isInstanceOf(ValidationException.class).hasMessage(PW_SHORT_MSG);
    }

    @Test
    void 실패_7자이면서_구성도_틀리면_짧다는_메시지가_먼저다() {
        assertThatThrownBy(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "abcdefg"))
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
    void 성공_공백이_섞여_있어도_세_종류를_모두_포함하면_통과한다() {
        assertThatCode(() -> UserValidation.validateRegistration(OK_USER, OK_EMAIL, "pass word 1!"))
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
