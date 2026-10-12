package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private static User member() {
        User user = new User("member01", "member01@example.com", "stored-hash", Role.USER, LocalDate.of(2000, 5, 5));
        ReflectionTestUtils.setField(user, "id", "11111111-2222-3333-4444-555555555555");
        return user;
    }

    @Test
    void 성공_탈퇴하면_개인정보가_지워지고_탈퇴_시각이_기록된다() {
        User user = member();
        Instant now = Instant.parse("2026-06-15T00:00:00Z");

        user.withdraw(now);

        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getDeletedAt()).isEqualTo(now);
        assertThat(user.getUsername()).isEqualTo("deleted-11111111-2222-3333-4444-555555555555");
        assertThat(user.getEmail()).isEqualTo("deleted-11111111-2222-3333-4444-555555555555@deleted.invalid");
        assertThat(user.getBirthDate()).isNull();
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void 성공_동의를_기록하면_시각과_정책_버전이_저장된다() {
        User user = member();
        Instant at = Instant.parse("2026-10-12T01:00:00Z");

        user.recordConsent(at, "2026-10-12");

        assertThat(user.getTermsAgreedAt()).isEqualTo(at);
        assertThat(user.getPrivacyAgreedAt()).isEqualTo(at);
        assertThat(user.getPolicyVersion()).isEqualTo("2026-10-12");
    }

    @Test
    void 성공_동의_기록이_없는_계정은_세_값이_모두_null이다() {
        User user = member();

        assertThat(user.getTermsAgreedAt()).isNull();
        assertThat(user.getPrivacyAgreedAt()).isNull();
        assertThat(user.getPolicyVersion()).isNull();
    }

    @Test
    void 성공_탈퇴해도_동의_기록은_그대로_남는다() {
        User user = member();
        Instant at = Instant.parse("2026-10-12T01:00:00Z");
        user.recordConsent(at, "2026-10-12");

        user.withdraw(Instant.parse("2026-10-20T00:00:00Z"));

        assertThat(user.getTermsAgreedAt()).isEqualTo(at);
        assertThat(user.getPrivacyAgreedAt()).isEqualTo(at);
        assertThat(user.getPolicyVersion()).isEqualTo("2026-10-12");
    }

    @Test
    void 성공_익명화한_아이디와_이메일은_컬럼_길이_안에_들어간다() {
        User user = member();

        user.withdraw(Instant.now());

        assertThat(user.getUsername().length()).isLessThanOrEqualTo(200);
        assertThat(user.getEmail().length()).isLessThanOrEqualTo(200);
    }

    @Test
    void 실패_탈퇴_전에는_삭제된_계정으로_취급하지_않는다() {
        assertThat(member().isDeleted()).isFalse();
    }

    @Test
    void 성공_탈퇴_후_비밀번호_해시는_어떤_비밀번호와도_예외_없이_불일치한다() {
        User user = member();
        user.withdraw(Instant.now());

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertThat(encoder.matches("pass1234!", user.getPasswordHash())).isFalse();
        assertThat(encoder.matches("!", user.getPasswordHash())).isFalse();
        assertThat(encoder.matches("", user.getPasswordHash())).isFalse();
    }
}
