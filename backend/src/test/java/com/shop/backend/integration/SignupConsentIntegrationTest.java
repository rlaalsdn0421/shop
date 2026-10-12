package com.shop.backend.integration;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.UserService;
import com.shop.backend.domain.entity.PolicyVersion;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.infrastructure.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Signup consent columns (V13) on a real Postgres: the mapping passes ddl-auto=validate, the values survive a
 * round trip, withdrawal keeps them, and accounts without a consent record stay NULL.
 * Full SpringBootTest like UserWithdrawalIntegrationTest (real Clock, security chain); Redis is not available
 * here and the rate limiter fails open by design.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignupConsentIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    private static final String PASSWORD = "pass1234!";
    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 1);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanTables() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
    }

    private static String registerBody(String username, String consentFields) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.com\","
                + "\"password\":\"" + PASSWORD + "\",\"birthDate\":\"1990-01-01\"" + consentFields + "}";
    }

    @Test
    void 성공_가입하면_동의_시각과_정책_버전이_저장되고_다시_읽힌다() {
        Instant before = Instant.now();

        User registered = authService.register("alice_01", "alice@example.com", PASSWORD, BIRTH, true, true);

        Instant after = Instant.now();
        Map<String, Object> r = row(registered.getId());
        assertThat(r.get("terms_agreed_at")).isNotNull();
        assertThat(r.get("privacy_agreed_at")).isNotNull();
        assertThat(r.get("policy_version")).isEqualTo(PolicyVersion.CURRENT);
        User loaded = userRepository.findById(registered.getId()).orElseThrow();
        assertThat(loaded.getPolicyVersion()).isEqualTo(PolicyVersion.CURRENT);
        assertThat(loaded.getTermsAgreedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(loaded.getPrivacyAgreedAt()).isEqualTo(loaded.getTermsAgreedAt());
    }

    @Test
    void 성공_HTTP로_가입해도_동의_기록이_저장된다() throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(
                mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                                .content(registerBody("bobby_01", ",\"termsAgreed\":true,\"privacyAgreed\":true")))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");

        Map<String, Object> r = row(id);
        assertThat(r.get("terms_agreed_at")).isNotNull();
        assertThat(r.get("privacy_agreed_at")).isNotNull();
        assertThat(r.get("policy_version")).isEqualTo(PolicyVersion.CURRENT);
    }

    @Test
    void 실패_동의가_빠졌거나_리터럴_true가_아니면_400이고_행이_생기지_않는다() throws Exception {
        String consentError = "이용약관과 개인정보 수집·이용에 동의해주세요.";
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("carol_01", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(consentError));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("carol_01", ",\"termsAgreed\":true,\"privacyAgreed\":false")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(consentError));
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("carol_01", ",\"termsAgreed\":\"true\",\"privacyAgreed\":1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 본문을 읽을 수 없습니다."));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void 성공_탈퇴해도_동의_기록은_그대로_보존된다() {
        User registered = authService.register("dave_01", "dave@example.com", PASSWORD, BIRTH, true, true);
        Map<String, Object> before = row(registered.getId());

        userService.withdraw(registered.getId(), PASSWORD);

        Map<String, Object> after = row(registered.getId());
        assertThat(after.get("deleted_at")).isNotNull();
        assertThat(after.get("username")).isEqualTo("deleted-" + registered.getId());
        assertThat(after.get("terms_agreed_at")).isNotNull().isEqualTo(before.get("terms_agreed_at"));
        assertThat(after.get("privacy_agreed_at")).isNotNull().isEqualTo(before.get("privacy_agreed_at"));
        assertThat(after.get("policy_version")).isEqualTo(PolicyVersion.CURRENT);
    }

    @Test
    void 성공_동의_기록이_없는_기존_가입자와_시드_계정은_세_컬럼이_모두_NULL이다() {
        // same constructors as UserSeeder (no email) and the pre-V13 registration path
        User admin = userRepository.save(new User("boss_01", null, passwordEncoder.encode(PASSWORD), Role.ADMIN));
        User legacy = userRepository.save(
                new User("old_user", "old@example.com", passwordEncoder.encode(PASSWORD), Role.USER, BIRTH));

        for (User u : new User[]{admin, legacy}) {
            Map<String, Object> r = row(u.getId());
            assertThat(r.get("terms_agreed_at")).isNull();
            assertThat(r.get("privacy_agreed_at")).isNull();
            assertThat(r.get("policy_version")).isNull();
            User loaded = userRepository.findById(u.getId()).orElseThrow();
            assertThat(loaded.getTermsAgreedAt()).isNull();
            assertThat(loaded.getPrivacyAgreedAt()).isNull();
            assertThat(loaded.getPolicyVersion()).isNull();
        }
    }
}
