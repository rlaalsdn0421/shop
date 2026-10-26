package com.shop.backend.integration;

import com.shop.backend.application.service.UserService;
import com.shop.backend.application.service.WishlistService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.InvalidCredentialsException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.WishlistRepository;
import com.shop.backend.infrastructure.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wishlist on a real Postgres: V21 shape, the idempotent ON CONFLICT insert, the 200 limit, per-user isolation, ordering,
 * races, account withdrawal, and the security chain over HTTP. A full SpringBootTest (like UserWithdrawalIntegrationTest)
 * so the real JwtAuthFilter/SecurityConfig are exercised. Redis (rate limit) is absent; the limiter fails open.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WishlistIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    private static final String PASSWORD = "pass1234!";
    private static final LocalDate BIRTH = LocalDate.of(1990, 1, 1);
    private static final int LIMIT = 200;
    // Cost 4: the hash carries its own cost, so withdraw's password check stays fast in the race test.
    private static final String FAST_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

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
    private WishlistService wishlistService;
    @Autowired
    private WishlistRepository wishlistRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private TestData data;
    private TransactionTemplate tx;

    @BeforeEach
    void cleanTables() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        jdbc.execute("TRUNCATE wishlist_items, order_items, reviews, orders, products, users CASCADE");
        data = new TestData(em, txManager);
        tx = new TransactionTemplate(txManager);
    }

    // ---- helpers ----

    private User member(String username) {
        User user = new User(username, username + "@example.com", passwordEncoder.encode(PASSWORD), Role.USER, BIRTH);
        tx.executeWithoutResult(s -> em.persist(user));
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    /** Products p000..p(n-1), all with the same createdAt. */
    private void products(int n) {
        for (int i = 0; i < n; i++) {
            data.product(pid(i), null, 1000 + i, null, TestData.T0);
        }
    }

    private static String pid(int i) {
        return String.format("p%03d", i);
    }

    private int rows(String userId) {
        return jdbc.queryForObject("SELECT count(*) FROM wishlist_items WHERE user_id = ?", Integer.class, userId);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM wishlist_items", Integer.class);
    }

    /** Gives {@code userId} the wishes p000..p(n-1) directly in SQL (fast filler for limit tests). */
    private void fill(String userId, int n) {
        jdbc.update("INSERT INTO wishlist_items (user_id, product_id, created_at) SELECT ?, id, now() FROM products WHERE id < ?",
                userId, pid(n));
        assertThat(rows(userId)).isEqualTo(n);
    }

    private void wishAt(String userId, String productId, String timestamp) {
        jdbc.update("INSERT INTO wishlist_items (user_id, product_id, created_at) VALUES (?, ?, ?::timestamptz)",
                userId, productId, timestamp);
    }

    private List<Map<String, Object>> dump() {
        return jdbc.queryForList("SELECT * FROM wishlist_items ORDER BY user_id, product_id");
    }

    // ---- 1. migration / table shape ----

    @Test
    void 성공_V21_마이그레이션이_적용되고_테이블_모양이_설계와_같다() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '21'", Boolean.class)).isTrue();

        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_name = 'wishlist_items' AND table_schema = current_schema() ORDER BY column_name");
        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("created_at", "product_id", "user_id");
        assertThat(columns).allMatch(c -> "NO".equals(c.get("is_nullable")));
        assertThat(columns.get(0).get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(String.valueOf(columns.get(0).get("column_default"))).containsIgnoringCase("now()");
        assertThat(columns.get(1).get("data_type")).isEqualTo("character varying");
        assertThat(columns.get(1).get("character_maximum_length")).isEqualTo(36);
        assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(36);
    }

    @Test
    void 성공_기본_키는_user_id_product_id_순서이고_외래_키는_users_products를_가리킨다() {
        List<String> pk = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                        + "WHERE i.indrelid = 'wishlist_items'::regclass AND i.indisprimary "
                        + "ORDER BY array_position(i.indkey::int[], a.attnum::int)", String.class);
        assertThat(pk).containsExactly("user_id", "product_id");

        List<String> fkTargets = jdbc.queryForList(
                "SELECT confrelid::regclass::text || '.' || (SELECT attname FROM pg_attribute WHERE attrelid = confrelid AND attnum = confkey[1]) "
                        + "|| '<-' || (SELECT attname FROM pg_attribute WHERE attrelid = conrelid AND attnum = conkey[1]) "
                        + "FROM pg_constraint WHERE conrelid = 'wishlist_items'::regclass AND contype = 'f'", String.class);
        assertThat(fkTargets).containsExactlyInAnyOrder("users.id<-user_id", "products.id<-product_id");
    }

    @Test
    void 성공_인덱스는_기본_키_하나뿐이고_보조_인덱스는_없다() {
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'wishlist_items' AND schemaname = current_schema()");

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).get("indexname")).isEqualTo("wishlist_items_pkey");
        assertThat((String) indexes.get(0).get("indexdef")).contains("(user_id, product_id)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_wishlist_user_created'", Integer.class))
                .isZero();
    }

    @Test
    void 실패_없는_사용자나_없는_상품의_행은_외래_키가_막고_같은_쌍의_두_번째_행은_기본_키가_막는다() {
        products(1);
        User user = member("alice_01");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO wishlist_items (user_id, product_id) VALUES (?, ?)", "ghost-user", pid(0)))
                .hasMessageContaining("wishlist_items_user_id_fkey");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO wishlist_items (user_id, product_id) VALUES (?, ?)", user.getId(), "ghost-product"))
                .hasMessageContaining("wishlist_items_product_id_fkey");
        jdbc.update("INSERT INTO wishlist_items (user_id, product_id) VALUES (?, ?)", user.getId(), pid(0));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO wishlist_items (user_id, product_id) VALUES (?, ?)", user.getId(), pid(0)))
                .hasMessageContaining("wishlist_items_pkey");
        assertThat(rows()).isEqualTo(1);
    }

    // ---- 2. ON CONFLICT insert ----

    @Test
    void 성공_insertIfAbsent는_처음에는_1_두_번째는_0을_돌려주고_행은_하나다() {
        products(1);
        User user = member("alice_01");

        Integer first = tx.execute(s -> wishlistRepository.insertIfAbsent(user.getId(), pid(0)));
        Integer second = tx.execute(s -> wishlistRepository.insertIfAbsent(user.getId(), pid(0)));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(rows(user.getId())).isEqualTo(1);
    }

    // ---- 3. add / remove ----

    @Test
    void 성공_같은_상품을_두_번_찜해도_행은_하나이고_created_at은_처음_값이다() throws Exception {
        products(1);
        User user = member("alice_01");

        wishlistService.add(user.getId(), pid(0));
        Object firstCreatedAt = jdbc.queryForObject("SELECT created_at FROM wishlist_items WHERE user_id = ?", Object.class, user.getId());
        Thread.sleep(30);
        wishlistService.add(user.getId(), pid(0));

        assertThat(rows(user.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM wishlist_items WHERE user_id = ?", Object.class, user.getId()))
                .isEqualTo(firstCreatedAt);
    }

    @Test
    void 성공_찜_해제는_멱등이다_찜하지_않은_상품을_해제해도_예외가_없고_다른_행은_그대로다() {
        products(2);
        User user = member("alice_01");
        wishlistService.add(user.getId(), pid(0));

        wishlistService.remove(user.getId(), pid(1)); // never wished
        assertThat(rows(user.getId())).isEqualTo(1);

        wishlistService.remove(user.getId(), pid(0));
        wishlistService.remove(user.getId(), pid(0)); // twice
        wishlistService.remove(user.getId(), "no-such-product");
        assertThat(rows()).isZero();
    }

    @Test
    void 실패_없는_상품을_찜하면_ProductNotFoundException이고_행이_생기지_않는다() {
        products(1);
        User user = member("alice_01");

        assertThatThrownBy(() -> wishlistService.add(user.getId(), "no-such-product")).isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> wishlistService.add(user.getId(), "x".repeat(100))).isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> wishlistService.add(user.getId(), "")).isInstanceOf(ProductNotFoundException.class);

        assertThat(rows()).isZero();
    }

    // ---- 4. the 200 limit with real counts ----

    @Test
    void 경계_199개에서_200번째는_담기고_201번째_다른_상품은_거절되며_행은_200개로_남는다() {
        products(LIMIT + 1);
        User user = member("alice_01");
        fill(user.getId(), LIMIT - 1);

        wishlistService.add(user.getId(), pid(LIMIT - 1)); // 200th
        assertThat(rows(user.getId())).isEqualTo(LIMIT);

        assertThatThrownBy(() -> wishlistService.add(user.getId(), pid(LIMIT)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("찜은 최대 200개까지 담을 수 있어요.");
        assertThat(rows(user.getId())).isEqualTo(LIMIT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM wishlist_items WHERE product_id = ?", Integer.class, pid(LIMIT))).isZero();
    }

    @Test
    void 성공_200개일_때_이미_찜한_상품을_다시_찜해도_예외가_없고_하나_해제하면_새_상품을_담을_수_있다() {
        products(LIMIT + 1);
        User user = member("alice_01");
        fill(user.getId(), LIMIT);

        wishlistService.add(user.getId(), pid(5)); // already wished at the limit: fine
        assertThat(rows(user.getId())).isEqualTo(LIMIT);
        assertThatThrownBy(() -> wishlistService.add(user.getId(), pid(LIMIT))).isInstanceOf(ValidationException.class);

        wishlistService.remove(user.getId(), pid(5));
        wishlistService.add(user.getId(), pid(LIMIT));

        assertThat(rows(user.getId())).isEqualTo(LIMIT);
        assertThat(wishlistService.ids(user.getId())).contains(pid(LIMIT)).doesNotContain(pid(5));
    }

    @Test
    void 성공_한도는_사용자별이라_한_사람이_가득_차도_다른_사람은_담을_수_있다() {
        products(LIMIT + 1);
        User full = member("alice_01");
        User other = member("bobby_01");
        fill(full.getId(), LIMIT);

        wishlistService.add(other.getId(), pid(LIMIT));

        assertThat(rows(other.getId())).isEqualTo(1);
        assertThat(rows(full.getId())).isEqualTo(LIMIT);
    }

    // ---- 5. list / ids: newest first, per user ----

    @Test
    void 성공_list와_ids는_최신_찜_순이고_서로_순서가_같다() throws Exception {
        products(3);
        User user = member("alice_01");
        for (String id : List.of(pid(1), pid(2), pid(0))) { // wish order p1, p2, p0
            wishlistService.add(user.getId(), id);
            Thread.sleep(10);
        }

        List<String> ids = wishlistService.ids(user.getId());
        List<String> listed = wishlistService.list(user.getId()).stream().map(Product::getId).toList();

        assertThat(ids).containsExactly(pid(0), pid(2), pid(1));
        assertThat(listed).containsExactlyElementsOf(ids);
        assertThat(wishlistService.list(user.getId())).extracting(Product::getName).containsExactly("상품 p000", "상품 p002", "상품 p001");
    }

    @Test
    void 성공_정렬은_상품_생성일이나_id가_아니라_찜한_시각이다_같은_시각이면_상품_id_순이다() {
        products(4);
        User user = member("alice_01");
        // insertion order and product order deliberately differ from created_at order
        wishAt(user.getId(), pid(3), "2026-03-03T00:00:00Z");
        wishAt(user.getId(), pid(0), "2026-03-01T00:00:00Z");
        wishAt(user.getId(), pid(2), "2026-03-02T00:00:00Z");
        wishAt(user.getId(), pid(1), "2026-03-02T00:00:00Z"); // ties with p2: smaller id first

        List<String> expected = List.of(pid(3), pid(1), pid(2), pid(0));
        assertThat(wishlistService.ids(user.getId())).containsExactlyElementsOf(expected);
        assertThat(wishlistService.list(user.getId())).extracting(Product::getId).containsExactlyElementsOf(expected);
    }

    @Test
    void 성공_목록은_그_사용자의_찜만_담고_다른_사용자의_찜과_같은_상품을_찜해도_서로_독립이다() {
        products(3);
        User alice = member("alice_01");
        User bobby = member("bobby_01");
        User nobody = member("carol_01");
        wishlistService.add(alice.getId(), pid(0));
        wishlistService.add(alice.getId(), pid(1));
        wishlistService.add(bobby.getId(), pid(1)); // same product as alice
        wishlistService.add(bobby.getId(), pid(2));

        assertThat(wishlistService.ids(alice.getId())).containsExactlyInAnyOrder(pid(0), pid(1));
        assertThat(wishlistService.ids(bobby.getId())).containsExactlyInAnyOrder(pid(1), pid(2));
        assertThat(wishlistService.list(alice.getId())).extracting(Product::getId).containsExactlyInAnyOrder(pid(0), pid(1));
        assertThat(wishlistService.list(bobby.getId())).extracting(Product::getId).containsExactlyInAnyOrder(pid(1), pid(2));
        assertThat(wishlistService.ids(nobody.getId())).isEmpty();
        assertThat(wishlistService.list(nobody.getId())).isEmpty();

        // one user's remove / second wish never touches the other's row
        wishlistService.remove(nobody.getId(), pid(1));
        wishlistService.remove(alice.getId(), pid(1));
        assertThat(wishlistService.ids(alice.getId())).containsExactly(pid(0));
        assertThat(wishlistService.ids(bobby.getId())).containsExactlyInAnyOrder(pid(1), pid(2));
        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void 성공_ids와_list는_200개까지만_돌려준다() {
        products(LIMIT + 1);
        User user = member("alice_01");
        fill(user.getId(), LIMIT);
        // 1 more than the limit (as a past race could leave behind)
        jdbc.update("INSERT INTO wishlist_items (user_id, product_id, created_at) VALUES (?, ?, now())", user.getId(), pid(LIMIT));

        assertThat(rows(user.getId())).isEqualTo(LIMIT + 1);
        assertThat(wishlistService.ids(user.getId())).hasSize(LIMIT);
        assertThat(wishlistService.list(user.getId())).hasSize(LIMIT);
    }

    // ---- 6. account withdrawal ----

    @Test
    void 성공_탈퇴하면_그_사용자의_찜이_모두_사라지고_다른_사용자의_찜은_그대로다() {
        products(5);
        User leaver = member("alice_01");
        User stayer = member("bobby_01");
        for (int i = 0; i < 5; i++) {
            wishlistService.add(leaver.getId(), pid(i));
        }
        wishlistService.add(stayer.getId(), pid(0));
        wishlistService.add(stayer.getId(), pid(3));
        List<Map<String, Object>> stayerBefore = jdbc.queryForList(
                "SELECT * FROM wishlist_items WHERE user_id = ? ORDER BY product_id", stayer.getId());

        userService.withdraw(leaver.getId(), PASSWORD);

        assertThat(rows(leaver.getId())).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM wishlist_items WHERE user_id = ? ORDER BY product_id", stayer.getId()))
                .isEqualTo(stayerBefore).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, leaver.getId())).isTrue();
    }

    @Test
    void 성공_찜이_없는_사용자도_탈퇴된다() {
        User user = member("alice_01");

        userService.withdraw(user.getId(), PASSWORD);

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.getId())).isTrue();
    }

    @Test
    void 실패_비밀번호가_틀려_탈퇴가_거절되면_찜은_그대로다() {
        products(2);
        User user = member("alice_01");
        wishlistService.add(user.getId(), pid(0));
        wishlistService.add(user.getId(), pid(1));
        List<Map<String, Object>> before = dump();

        assertThatThrownBy(() -> userService.withdraw(user.getId(), "wrong-pass1!"))
                .isInstanceOf(com.shop.backend.domain.error.InvalidCredentialsException.class);

        assertThat(dump()).isEqualTo(before).hasSize(2);
    }

    @Test
    void 실패_관리자와_판매자는_탈퇴할_수_없고_그들의_찜은_그대로다() {
        products(1);
        User seller = new User("seller_01", "seller@example.com", passwordEncoder.encode(PASSWORD), Role.SELLER, BIRTH);
        tx.executeWithoutResult(s -> em.persist(seller));
        wishlistService.add(seller.getId(), pid(0));

        assertThatThrownBy(() -> userService.withdraw(seller.getId(), PASSWORD))
                .isInstanceOf(com.shop.backend.domain.error.WithdrawalNotAllowedException.class);

        assertThat(rows(seller.getId())).isEqualTo(1);
    }

    // ---- 7. concurrency ----

    private List<Throwable> runTogether(List<Runnable> jobs) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(jobs.size());
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable job : jobs) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        job.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            for (Future<Throwable> f : futures) {
                Throwable t = f.get(60, TimeUnit.SECONDS);
                if (t != null) {
                    failures.add(t);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    @Test
    void 성공_같은_상품을_동시에_찜해도_행은_하나이고_예외가_없다() throws Exception {
        products(1);
        User user = member("alice_01");
        final int threads = 8;
        for (int round = 0; round < 5; round++) { // timing dependent: several rounds
            jdbc.update("DELETE FROM wishlist_items");
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                jobs.add(() -> wishlistService.add(user.getId(), pid(0)));
            }

            List<Throwable> failures = runTogether(jobs);

            assertThat(failures).as("a duplicate wish must be absorbed by ON CONFLICT, not surface as an error").isEmpty();
            assertThat(rows(user.getId())).isEqualTo(1);
        }
    }

    @Test
    void 성공_한도_근처에서_서로_다른_상품을_동시에_찜해도_정확히_200개에서_멈추고_나머지는_검증_예외다() throws Exception {
        final int threads = 12;
        final int freeSlots = 5;
        products(LIMIT - freeSlots + threads);
        User user = member("alice_01");
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM wishlist_items");
            fill(user.getId(), LIMIT - freeSlots);
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String productId = pid(LIMIT - freeSlots + i);
                jobs.add(() -> wishlistService.add(user.getId(), productId));
            }

            List<Throwable> failures = runTogether(jobs);

            assertThat(failures).as("round %d: only the limit rejection is acceptable", round)
                    .allMatch(t -> t instanceof ValidationException, failures.toString());
            assertThat(rows(user.getId())).as("round %d: the cap is exact (adds of one user are serialized)", round).isEqualTo(LIMIT);
            assertThat(failures).as("round %d: every request beyond the free slots is rejected", round).hasSize(threads - freeSlots);
        }
    }

    @Test
    void 성공_서로_다른_사용자의_동시_찜은_각자_한도를_따로_센다() throws Exception {
        products(LIMIT + 1);
        User a = member("alice_01");
        User b = member("bobby_01");
        fill(a.getId(), LIMIT - 1);
        fill(b.getId(), LIMIT - 1);
        List<Runnable> jobs = new ArrayList<>();
        for (User u : List.of(a, b)) {
            jobs.add(() -> wishlistService.add(u.getId(), pid(LIMIT - 1)));
            jobs.add(() -> wishlistService.add(u.getId(), pid(LIMIT)));
        }

        List<Throwable> failures = runTogether(jobs);

        assertThat(failures).hasSize(2).allMatch(t -> t instanceof ValidationException); // one loser per user
        assertThat(rows(a.getId())).isEqualTo(LIMIT);
        assertThat(rows(b.getId())).isEqualTo(LIMIT);
    }

    // ---- 7b. user row lock: withdrawal race, lock_timeout, independence between users ----

    /** Withdrawn-account row without the BCrypt cost of the app's encoder; the password is PASSWORD. */
    private User fastMember(String username) {
        User user = new User(username, username + "@example.com", FAST_HASH, Role.USER, BIRTH);
        tx.executeWithoutResult(s -> em.persist(user));
        return user;
    }

    @Test
    void 실패_이미_탈퇴한_사용자의_찜은_자격_증명_예외이고_행이_생기지_않는다() {
        products(1);
        User user = fastMember("gone_0001");
        userService.withdraw(user.getId(), PASSWORD);

        assertThatThrownBy(() -> wishlistService.add(user.getId(), pid(0))).isInstanceOf(InvalidCredentialsException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void 실패_없는_사용자_id의_찜은_자격_증명_예외다() {
        products(1);

        assertThatThrownBy(() -> wishlistService.add("no-such-user", pid(0))).isInstanceOf(InvalidCredentialsException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void 성공_찜하기와_탈퇴가_동시에_일어나도_어떤_순서든_탈퇴한_사용자의_찜은_남지_않는다() throws Exception {
        products(3);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int added = 0;
        int rejected = 0;
        try {
            for (int round = 0; round < 150; round++) {
                User user = fastMember("racer_" + round);
                CyclicBarrier startTogether = new CyclicBarrier(2);
                long offsetNanos = ((round % 9) - 4) * 400_000L; // sweep the relative start (-1.6 ms .. +1.6 ms)

                Future<Throwable> add = pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    LockSupport.parkNanos(Math.max(0, offsetNanos));
                    try {
                        wishlistService.add(user.getId(), pid(0));
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                });
                Future<Void> withdraw = pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    LockSupport.parkNanos(Math.max(0, -offsetNanos));
                    userService.withdraw(user.getId(), PASSWORD);
                    return null;
                });

                Throwable addResult = add.get(30, TimeUnit.SECONDS);
                withdraw.get(30, TimeUnit.SECONDS);

                assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.getId()))
                        .as("round %d: withdrawn", round).isTrue();
                assertThat(rows(user.getId())).as("round %d: no wishlist row left behind (personal data)", round).isZero();
                if (addResult == null) {
                    added++;
                } else {
                    assertThat(addResult).as("round %d: the only allowed failure", round).isInstanceOf(InvalidCredentialsException.class);
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(added + rejected).isEqualTo(150);
    }

    /** Holds {@code sql} (a row-locking statement for the given user) in an open transaction while {@code action} runs. */
    private <T> T whileUserRowIsHeld(String userId, String lockingSql, Callable<T> action, long waitMillis, boolean expectBlocked)
            throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList(lockingSql, userId);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<T> result = pool.submit(action);
            try {
                T value = result.get(waitMillis, TimeUnit.MILLISECONDS);
                assertThat(expectBlocked).as("the call returned while the user row was held").isFalse();
                return value;
            } catch (TimeoutException blocked) {
                assertThat(expectBlocked).as("the call is still waiting for the user row").isTrue();
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
            return result.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 성공_한_사용자의_행을_잡고_있어도_다른_사용자의_찜은_기다리지_않고_그_사용자의_찜만_기다린다() throws Exception {
        products(1);
        User held = member("alice_01");
        User other = member("bobby_01");
        String lockSql = "SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE";

        // the other user's add finishes while alice's row is locked
        whileUserRowIsHeld(held.getId(), lockSql, () -> {
            wishlistService.add(other.getId(), pid(0));
            return null;
        }, 2_000, false);
        assertThat(rows(other.getId())).isEqualTo(1);

        // alice's own add waits for the lock, then goes through once it is released
        whileUserRowIsHeld(held.getId(), lockSql, () -> {
            wishlistService.add(held.getId(), pid(0));
            return null;
        }, 800, true);
        assertThat(rows(held.getId())).isEqualTo(1);
    }

    @Test
    void 성공_탈퇴가_커밋되기_전의_찜은_기다렸다가_커밋_뒤에_거절되고_저장되지_않는다() throws Exception {
        products(1);
        User user = member("waiter_01");

        assertThatThrownBy(() -> whileUserRowIsHeld(user.getId(), "UPDATE users SET deleted_at = now() WHERE id = ? RETURNING id",
                () -> {
                    wishlistService.add(user.getId(), pid(0));
                    return null;
                }, 800, true))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(InvalidCredentialsException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void 실패_사용자_행을_3초_넘게_잡고_있으면_찜은_락_대기_실패로_거절되고_저장되지_않는다() throws Exception {
        products(1);
        User user = member("waiter_02");
        long start = System.nanoTime();

        assertThatThrownBy(() -> whileUserRowIsHeld(user.getId(), "SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE",
                () -> {
                    wishlistService.add(user.getId(), pid(0));
                    return null;
                }, 10_000, false))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PessimisticLockingFailureException.class); // GlobalExceptionHandler -> 503

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMillis).as("lock_timeout is 3 s").isBetween(2_500L, 9_000L);
        assertThat(rows()).isZero();
    }

    @Test
    void 성공_찜하기는_주문처럼_사용자_행에_FK_공유_잠금만_거는_작업과_서로_막지_않는다() throws Exception {
        products(1);
        User user = member("orderer_1");
        // FOR KEY SHARE is what an FK insert (e.g. an order) takes on the users row; it must not conflict with FOR NO KEY UPDATE
        whileUserRowIsHeld(user.getId(), "SELECT id FROM users WHERE id = ? FOR KEY SHARE", () -> {
            wishlistService.add(user.getId(), pid(0));
            return null;
        }, 2_000, false);

        assertThat(rows(user.getId())).isEqualTo(1);
    }

    @Test
    void 성공_찜하기와_해제가_동시에_와도_예외가_없고_행은_0개_또는_1개다() throws Exception {
        products(1);
        User user = member("alice_01");
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM wishlist_items");
            List<Runnable> jobs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                jobs.add(() -> wishlistService.add(user.getId(), pid(0)));
                jobs.add(() -> wishlistService.remove(user.getId(), pid(0)));
            }

            assertThat(runTogether(jobs)).isEmpty();
            assertThat(rows(user.getId())).isBetween(0, 1);
        }
    }

    // ---- 8. HTTP through the real security chain ----

    @Test
    void 성공_HTTP로_찜하고_조회하고_해제한다() throws Exception {
        products(2);
        User user = member("alice_01");
        String token = bearer(user);

        mockMvc.perform(put("/api/wishlist/" + pid(0)).header("Authorization", token)).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/wishlist/" + pid(0)).header("Authorization", token)).andExpect(status().isNoContent()); // idempotent
        mockMvc.perform(put("/api/wishlist/" + pid(1)).header("Authorization", token)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/wishlist/ids").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids.length()").value(2));
        mockMvc.perform(get("/api/wishlist").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(pid(1)))
                .andExpect(jsonPath("$[1].id").value(pid(0)))
                .andExpect(jsonPath("$[0].price").value(1001));

        mockMvc.perform(delete("/api/wishlist/" + pid(1)).header("Authorization", token)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/wishlist/" + pid(1)).header("Authorization", token)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/wishlist/ids").header("Authorization", token))
                .andExpect(jsonPath("$.ids.length()").value(1))
                .andExpect(jsonPath("$.ids[0]").value(pid(0)));
    }

    @Test
    void 실패_HTTP로_없는_상품은_404_한도_초과는_400이고_행이_늘지_않는다() throws Exception {
        products(LIMIT + 1);
        User user = member("alice_01");
        fill(user.getId(), LIMIT);
        String token = bearer(user);

        mockMvc.perform(put("/api/wishlist/no-such-product").header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/wishlist/" + "x".repeat(100)).header("Authorization", token)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/wishlist/" + pid(LIMIT)).header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("찜은 최대 200개까지 담을 수 있어요."));

        assertThat(rows(user.getId())).isEqualTo(LIMIT);
    }

    @Test
    void 실패_HTTP로_토큰이_없으면_네_API_모두_401이고_DB는_그대로다() throws Exception {
        products(1);
        User user = member("alice_01");
        wishlistService.add(user.getId(), pid(0));
        List<Map<String, Object>> before = dump();

        mockMvc.perform(get("/api/wishlist/ids")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mockMvc.perform(get("/api/wishlist")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/wishlist/" + pid(0))).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/wishlist/" + pid(0))).andExpect(status().isUnauthorized());

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_탈퇴한_사용자의_기존_토큰은_네_API_모두_401이고_찜을_만들지_못한다() throws Exception {
        products(1);
        User user = member("alice_01");
        String token = bearer(user);
        userService.withdraw(user.getId(), PASSWORD);

        mockMvc.perform(get("/api/wishlist/ids").header("Authorization", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/wishlist").header("Authorization", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/wishlist/" + pid(0)).header("Authorization", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/wishlist/" + pid(0)).header("Authorization", token)).andExpect(status().isUnauthorized());

        assertThat(rows()).isZero();
    }

    @Test
    void 성공_HTTP로_다른_사용자의_토큰은_자기_찜만_본다() throws Exception {
        products(2);
        User alice = member("alice_01");
        User bobby = member("bobby_01");
        wishlistService.add(alice.getId(), pid(0));

        mockMvc.perform(get("/api/wishlist/ids").header("Authorization", bearer(bobby)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids.length()").value(0));
        mockMvc.perform(get("/api/wishlist/ids?userId=" + alice.getId()).header("Authorization", bearer(bobby)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ids.length()").value(0));
        mockMvc.perform(delete("/api/wishlist/" + pid(0)).header("Authorization", bearer(bobby))).andExpect(status().isNoContent());

        assertThat(wishlistService.ids(alice.getId())).containsExactly(pid(0)); // bobby's delete did not touch alice's row
    }
}
