package com.shop.backend.integration;

import com.shop.backend.application.service.AuthService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.ReviewService;
import com.shop.backend.application.service.UserService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Review;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.domain.error.WithdrawalNotAllowedException;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.infrastructure.repository.ReviewRepository;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Account withdrawal on a real Postgres, through the real security chain (JwtAuthFilter + SecurityConfig) with MockMvc.
 * A full SpringBootTest (not the JPA slice of the other integration tests) because the "token of a withdrawn user must
 * be 401, not an FK 500" rule lives in the filter chain. Shares the one Testcontainers Postgres of the other tests.
 * Redis (rate limit) is not available here; the limiter fails open by design, which only costs a short timeout.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserWithdrawalIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    private static final String PASSWORD = "pass1234!";
    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 1);
    private static final String KEY = "order-key-0001";
    private static final String ORDER_BODY =
            "{\"customerName\":\"구매자\",\"customerPhone\":\"010-0000-0000\",\"customerAddress\":\"서울\","
                    + "\"items\":[{\"productId\":\"p1\",\"quantity\":1}]}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private UserService userService;
    @Autowired
    private AuthService authService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestData data;
    private TransactionTemplate tx;

    @BeforeEach
    void cleanTables() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        data = new TestData(em, txManager);
        tx = new TransactionTemplate(txManager);
    }

    // ---- helpers ----

    private User member(String username, String email, String rawPassword, Role role) {
        User user = new User(username, email, passwordEncoder.encode(rawPassword), role, BIRTH);
        tx.executeWithoutResult(s -> em.persist(user));
        return user;
    }

    private User member(String username) {
        return member(username, username + "@example.com", PASSWORD, Role.USER);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private void review(String productId, User user, int rating, String name) {
        tx.executeWithoutResult(s -> em.persist(
                new Review(em.getReference(Product.class, productId), user.getId(), name, rating, "본문-" + user.getUsername())));
    }

    private void assertAnonymized(User user) {
        Map<String, Object> r = row(user.getId());
        assertThat(r.get("username")).isEqualTo("deleted-" + user.getId());
        assertThat(r.get("email")).isEqualTo("deleted-" + user.getId() + "@deleted.invalid");
        assertThat(r.get("password_hash")).isEqualTo("!");
        assertThat(r.get("birth_date")).isNull();
        assertThat(r.get("deleted_at")).isNotNull();
    }

    // ---- 1. anonymization ----

    @Test
    void 성공_탈퇴하면_행이_익명화되고_deleted_at이_채워진다() {
        User user = member("alice_01");

        userService.withdraw(user.getId(), PASSWORD);

        assertAnonymized(user);
        assertThat(row(user.getId()).get("role")).isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1); // row kept
    }

    @Test
    void 성공_두_명이_탈퇴해도_유니크_제약과_충돌하지_않고_같은_아이디_이메일로_다시_가입할_수_있다() {
        User a = member("alice_01", "alice@example.com", PASSWORD, Role.USER);
        User b = member("bobby_01", "bobby@example.com", PASSWORD, Role.USER);

        userService.withdraw(a.getId(), PASSWORD);
        userService.withdraw(b.getId(), PASSWORD);

        assertAnonymized(a);
        assertAnonymized(b);
        User again = authService.register("alice_01", "alice@example.com", PASSWORD, BIRTH, true, true);
        assertThat(again.getId()).isNotEqualTo(a.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = 'alice_01' AND deleted_at IS NULL", Integer.class))
                .isEqualTo(1);
        assertThat(authService.login("alice_01", PASSWORD).username()).isEqualTo("alice_01");
    }

    @Test
    void 실패_탈퇴하지_않은_계정의_아이디는_여전히_중복으로_가입이_거절된다() {
        member("alice_01", "alice@example.com", PASSWORD, Role.USER);

        assertThatThrownBy(() -> authService.register("alice_01", "other@example.com", PASSWORD, BIRTH, true, true))
                .isInstanceOf(com.shop.backend.domain.error.DuplicateUsernameException.class);
    }

    // ---- 2. renameReviewer on real Postgres ----

    @Test
    void 성공_탈퇴하면_그_사용자의_모든_리뷰_이름만_바뀌고_다른_사용자_리뷰와_평점_집계는_그대로다() {
        data.product("p1", null, 1000, null, TestData.T0);
        data.product("p2", null, 1000, null, TestData.T0);
        User leaver = member("alice_01");
        User stayer = member("bobby_01");
        review("p1", leaver, 5, "alice_01");
        review("p2", leaver, 4, "alice_01");
        review("p1", stayer, 2, "bobby_01");
        data.review("p1", 3); // legacy anonymous review (user_id NULL)
        double avgBefore = reviewService.listReviews("p1").averageRating();
        int countBefore = reviewService.listReviews("p1").reviewCount();
        List<Map<String, Object>> othersBefore = jdbc.queryForList(
                "SELECT * FROM reviews WHERE user_id IS DISTINCT FROM ? ORDER BY id", leaver.getId());

        userService.withdraw(leaver.getId(), PASSWORD);

        assertThat(jdbc.queryForList("SELECT reviewer_name FROM reviews WHERE user_id = ?", String.class, leaver.getId()))
                .hasSize(2).containsOnly("탈퇴한 회원");
        // untouched: other users' and legacy reviews (every column), and the leaver's rating/comment/user_id/product
        assertThat(jdbc.queryForList("SELECT * FROM reviews WHERE user_id IS DISTINCT FROM ? ORDER BY id", leaver.getId()))
                .isEqualTo(othersBefore);
        assertThat(jdbc.queryForList("SELECT rating, comment, product_id FROM reviews WHERE user_id = ? ORDER BY product_id",
                leaver.getId()))
                .containsExactly(
                        Map.of("rating", 5, "comment", "본문-alice_01", "product_id", "p1"),
                        Map.of("rating", 4, "comment", "본문-alice_01", "product_id", "p2"));
        // the existing aggregation (service + SQL) is unchanged
        var after = reviewService.listReviews("p1");
        assertThat(after.averageRating()).isEqualTo(avgBefore);
        assertThat(after.reviewCount()).isEqualTo(countBefore).isEqualTo(3);
        assertThat(after.reviews()).extracting(Review::getReviewerName)
                .containsExactlyInAnyOrder("탈퇴한 회원", "bobby_01", "작성자");
    }

    @Test
    void 성공_탈퇴_트랜잭션이_끝난_뒤_새_영속성_컨텍스트로_다시_읽어도_이름이_바뀌어_있다() {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        review("p1", user, 5, "alice_01");
        String reviewId = jdbc.queryForObject("SELECT id FROM reviews", String.class);

        userService.withdraw(user.getId(), PASSWORD);

        // new transaction = new persistence context: must hit the DB, not a stale cache
        Review reloaded = tx.execute(s -> em.find(Review.class, reviewId));
        assertThat(reloaded.getReviewerName()).isEqualTo("탈퇴한 회원");
        User reloadedUser = tx.execute(s -> userRepository.findById(user.getId()).orElseThrow());
        assertThat(reloadedUser.isDeleted()).isTrue();
        assertThat(reloadedUser.getUsername()).isEqualTo("deleted-" + user.getId());
    }

    @Test
    void 성공_리뷰가_없는_사용자도_탈퇴된다() {
        User user = member("alice_01");

        userService.withdraw(user.getId(), PASSWORD);

        assertAnonymized(user);
    }

    // ---- 3. orders are kept ----

    @Test
    void 성공_탈퇴해도_주문과_주문항목은_그대로이고_주문자_id로_계속_조회된다() {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        Order order = orderService.createOrder(user.getId(), KEY, "구매자", "010-0000-0000", "서울", List.of(new OrderLine("p1", 2)));
        Map<String, Object> orderBefore = jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.getId());
        List<Map<String, Object>> itemsBefore = jdbc.queryForList("SELECT * FROM order_items ORDER BY id");

        userService.withdraw(user.getId(), PASSWORD);

        assertThat(jdbc.queryForMap("SELECT * FROM orders WHERE id = ?", order.getId())).isEqualTo(orderBefore);
        assertThat(jdbc.queryForList("SELECT * FROM order_items ORDER BY id")).isEqualTo(itemsBefore).hasSize(1);
        assertThat(orderBefore.get("user_id")).isEqualTo(user.getId());
        assertThat(orderBefore.get("customer_name")).isEqualTo("구매자");
        assertThat(orderService.getOrder(order.getId(), user.getId())).isPresent();
        assertThat(orderService.listMyOrders(user.getId(), 10, null).orders()).hasSize(1);
    }

    @Test
    void 실패_다른_사용자의_주문은_탈퇴한_사용자도_조회할_수_없다() {
        data.product("p1", null, 1000, null, TestData.T0);
        User owner = member("alice_01");
        User leaver = member("bobby_01");
        Order order = orderService.createOrder(owner.getId(), KEY, "구매자", "010-0000-0000", "서울", List.of(new OrderLine("p1", 1)));

        userService.withdraw(leaver.getId(), PASSWORD);

        assertThat(orderService.getOrder(order.getId(), leaver.getId())).isEmpty();
    }

    // ---- 4. token of a withdrawn user ----

    @Test
    void 실패_탈퇴한_사용자의_기존_토큰으로_주문하면_FK_500이_아니라_401이고_주문도_재고_변화도_없다() throws Exception {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        String bearer = token(user);
        userService.withdraw(user.getId(), PASSWORD);

        mockMvc.perform(post("/api/orders").header("Authorization", bearer).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = 'p1'", Integer.class)).isEqualTo(100);
    }

    @Test
    void 성공_활성_사용자의_토큰으로는_같은_요청이_주문된다() throws Exception {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");

        mockMvc.perform(post("/api/orders").header("Authorization", token(user)).header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").isNotEmpty());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, user.getId())).isEqualTo(1);
    }

    @Test
    void 실패_탈퇴한_사용자의_토큰으로는_내_주문_목록과_주문_상세도_401이다() throws Exception {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        Order order = orderService.createOrder(user.getId(), KEY, "구매자", "010-0000-0000", "서울", List.of(new OrderLine("p1", 1)));
        String bearer = token(user);
        mockMvc.perform(get("/api/orders").header("Authorization", bearer)).andExpect(status().isOk()); // before: fine

        userService.withdraw(user.getId(), PASSWORD);

        mockMvc.perform(get("/api/orders").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/" + order.getId()).header("Authorization", bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    void 실패_존재하지_않는_사용자_id의_유효한_서명_토큰은_401이다() throws Exception {
        User ghost = new User("ghost_01", "ghost@example.com", "x", Role.USER);
        ReflectionTestUtils.setField(ghost, "id", "00000000-0000-0000-0000-000000000000");

        mockMvc.perform(get("/api/orders").header("Authorization", token(ghost))).andExpect(status().isUnauthorized());
    }

    @Test
    void 실패_서명이_틀린_토큰과_토큰_없음은_401이다() throws Exception {
        User user = member("alice_01");

        String valid = token(user);
        int sig = valid.lastIndexOf('.') + 1; // flip the first signature char (trailing chars may carry no bits)
        String forged = valid.substring(0, sig) + (valid.charAt(sig) == 'A' ? 'B' : 'A') + valid.substring(sig + 1);
        mockMvc.perform(get("/api/orders").header("Authorization", valid)).andExpect(status().isOk());
        mockMvc.perform(get("/api/orders").header("Authorization", forged)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void 성공_탈퇴한_사용자의_토큰이어도_공개_엔드포인트는_계속_열려_있다() throws Exception {
        User user = member("alice_01");
        String bearer = token(user);
        userService.withdraw(user.getId(), PASSWORD);

        mockMvc.perform(get("/api/products").header("Authorization", bearer)).andExpect(status().isOk());
    }

    // ---- end to end DELETE /api/users/me ----

    @Test
    void 성공_DELETE_users_me는_204이고_그_직후_같은_토큰은_401이다() throws Exception {
        User user = member("alice_01");
        String bearer = token(user);

        mockMvc.perform(delete("/api/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertAnonymized(user);
        mockMvc.perform(get("/api/orders").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        // and a second DELETE with the same (now dead) token is rejected by the filter, data unchanged
        Map<String, Object> afterFirst = row(user.getId());
        mockMvc.perform(delete("/api/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(row(user.getId())).isEqualTo(afterFirst);
    }

    @Test
    void 실패_DELETE_users_me는_토큰이_없으면_401이고_비밀번호가_틀리면_401_관리자는_403이며_행이_바뀌지_않는다() throws Exception {
        User user = member("alice_01");
        User admin = member("admin_01", "admin@example.com", PASSWORD, Role.ADMIN);
        Map<String, Object> userBefore = row(user.getId());
        Map<String, Object> adminBefore = row(admin.getId());

        mockMvc.perform(delete("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me").header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"wrong-pass1!\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me").header("Authorization", token(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/users/me").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        assertThat(row(user.getId())).isEqualTo(userBefore);
        assertThat(row(admin.getId())).isEqualTo(adminBefore);
    }

    // ---- 5. login ----

    @Test
    void 실패_탈퇴한_계정은_원래_아이디와_비밀번호로도_tombstone_아이디로도_로그인할_수_없다() {
        User user = member("alice_01");
        assertThat(authService.login("alice_01", PASSWORD).token()).isNotBlank(); // works before

        userService.withdraw(user.getId(), PASSWORD);

        assertThatThrownBy(() -> authService.login("alice_01", PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authService.login("deleted-" + user.getId(), PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authService.login("deleted-" + user.getId(), "!")).isInstanceOf(InvalidCredentialsException.class);
    }

    // ---- 6. re-authentication ----

    @Test
    void 실패_비밀번호가_틀리면_InvalidCredentials이고_행이_전혀_바뀌지_않는다() {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        review("p1", user, 5, "alice_01");
        Map<String, Object> before = row(user.getId());

        assertThatThrownBy(() -> userService.withdraw(user.getId(), "wrong-pass1!")).isInstanceOf(InvalidCredentialsException.class);

        assertThat(row(user.getId())).isEqualTo(before);
        assertThat(before.get("deleted_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT reviewer_name FROM reviews", String.class)).isEqualTo("alice_01");
    }

    @Test
    void 실패_빈_비밀번호는_ValidationException이고_행이_바뀌지_않는다() {
        User user = member("alice_01");
        Map<String, Object> before = row(user.getId());

        assertThatThrownBy(() -> userService.withdraw(user.getId(), null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> userService.withdraw(user.getId(), "")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> userService.withdraw(user.getId(), "   ")).isInstanceOf(ValidationException.class);

        assertThat(row(user.getId())).isEqualTo(before);
    }

    @Test
    void 실패_ADMIN과_SELLER는_비밀번호가_맞아도_WithdrawalNotAllowed이고_행이_바뀌지_않는다() {
        User admin = member("admin_01", "admin@example.com", PASSWORD, Role.ADMIN);
        User seller = member("seller_01", "seller@example.com", PASSWORD, Role.SELLER);
        Map<String, Object> adminBefore = row(admin.getId());
        Map<String, Object> sellerBefore = row(seller.getId());

        assertThatThrownBy(() -> userService.withdraw(admin.getId(), PASSWORD)).isInstanceOf(WithdrawalNotAllowedException.class);
        assertThatThrownBy(() -> userService.withdraw(seller.getId(), PASSWORD)).isInstanceOf(WithdrawalNotAllowedException.class);

        assertThat(row(admin.getId())).isEqualTo(adminBefore);
        assertThat(row(seller.getId())).isEqualTo(sellerBefore);
    }

    @Test
    void 실패_존재하지_않는_사용자_id는_InvalidCredentials다() {
        assertThatThrownBy(() -> userService.withdraw("no-such-user", PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
    }

    // ---- 7. already withdrawn ----

    @Test
    void 실패_이미_탈퇴한_계정에_다시_탈퇴를_요청하면_InvalidCredentials이고_데이터가_그대로다() {
        data.product("p1", null, 1000, null, TestData.T0);
        User user = member("alice_01");
        review("p1", user, 5, "alice_01");
        userService.withdraw(user.getId(), PASSWORD);
        Map<String, Object> after = row(user.getId());

        assertThatThrownBy(() -> userService.withdraw(user.getId(), PASSWORD)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> userService.withdraw(user.getId(), "!")).isInstanceOf(InvalidCredentialsException.class);

        assertThat(row(user.getId())).isEqualTo(after);
        assertThat(jdbc.queryForObject("SELECT reviewer_name FROM reviews", String.class)).isEqualTo("탈퇴한 회원");
    }

    // ---- 8. concurrent withdrawal ----

    @Test
    void 성공_같은_사용자의_동시_탈퇴는_허용된_예외만_내고_최종_상태가_일관된다() throws Exception {
        final int threads = 6;
        for (int round = 0; round < 3; round++) { // timing dependent: several rounds
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            data.product("p1", null, 1000, null, TestData.T0);
            data.product("p2", null, 1000, null, TestData.T0);
            User user = member("alice_01");
            review("p1", user, 5, "alice_01");
            review("p2", user, 3, "alice_01");

            CyclicBarrier startTogether = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Throwable> failures = new ArrayList<>();
            int successes = 0;
            try {
                List<Future<Throwable>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(() -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        try {
                            userService.withdraw(user.getId(), PASSWORD);
                            return null;
                        } catch (Throwable t) {
                            return t;
                        }
                    }));
                }
                for (Future<Throwable> f : futures) {
                    Throwable t = f.get(60, TimeUnit.SECONDS);
                    if (t == null) {
                        successes++;
                    } else {
                        failures.add(t);
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(successes).as("at least one withdrawal wins").isGreaterThanOrEqualTo(1);
            assertThat(failures).as("only InvalidCredentials is acceptable, never a constraint violation or deadlock")
                    .allMatch(t -> t instanceof InvalidCredentialsException, failures.toString());
            assertAnonymized(user);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT reviewer_name FROM reviews", String.class)).hasSize(2).containsOnly("탈퇴한 회원");
        }
    }

    // ---- 9. migration V12 ----

    @Test
    void 성공_V12_이후_users_deleted_at은_널_허용_TIMESTAMP_컬럼이고_기존_행은_NULL이다() {
        Map<String, Object> column = jdbc.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'deleted_at' AND table_schema = current_schema()");
        assertThat(column.get("data_type")).isEqualTo("timestamp without time zone");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '12'", Boolean.class)).isTrue();

        User user = member("alice_01");
        assertThat(row(user.getId()).get("deleted_at")).isNull();
    }

    @Test
    void 성공_익명화_후에도_users의_유니크_제약_두_개가_그대로_있다() {
        List<String> names = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass AND contype = 'u'", String.class);
        assertThat(names).contains("uq_users_username", "uq_users_email");
    }

    // ---- 10. registration ----

    @Test
    void 실패_deleted_invalid_도메인_이메일로는_가입할_수_없고_비슷한_도메인은_가입된다() {
        assertThatThrownBy(() -> authService.register("sneaky_01", "deleted-abc@deleted.invalid", PASSWORD, BIRTH, true, true))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> authService.register("sneaky_02", "Someone@DELETED.INVALID", PASSWORD, BIRTH, true, true))
                .isInstanceOf(ValidationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();

        User ok = authService.register("fine_01", "fine@deleted.invalid.example.com", PASSWORD, BIRTH, true, true);
        assertThat(ok.getId()).isNotBlank();
    }
}
