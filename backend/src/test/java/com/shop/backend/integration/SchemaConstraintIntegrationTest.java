package com.shop.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What only a real Postgres can prove: migrations vs entities, CHECK constraints and (partial) unique indexes. */
class SchemaConstraintIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private Environment environment;

    private void insertProduct(String id, int price, Integer originalPrice) {
        jdbc.update("INSERT INTO products (id, name, description, price, image_url, stock, created_at, original_price) "
                        + "VALUES (?, 'n', 'd', ?, 'u', 1, ?, ?)",
                id, price, Timestamp.valueOf(LocalDateTime.now()), originalPrice);
    }

    private void insertReview(String id, String productId, String userId) {
        jdbc.update("INSERT INTO reviews (id, product_id, reviewer_name, rating, comment, created_at, user_id) "
                        + "VALUES (?, ?, 'r', 5, 'c', ?, ?)",
                id, productId, Timestamp.valueOf(LocalDateTime.now()), userId);
    }

    private void insertUser(String id, String username, String email) {
        jdbc.update("INSERT INTO users (id, username, email, password_hash, role, created_at) "
                        + "VALUES (?, ?, ?, 'h', 'USER', ?)",
                id, username, email, Timestamp.valueOf(LocalDateTime.now()));
    }

    private static String message(Throwable t) {
        return String.valueOf(((DataIntegrityViolationException) t).getMostSpecificCause().getMessage());
    }

    @Test
    void 성공_모든_마이그레이션이_적용되고_엔티티_검증이_켜져_있다() throws IOException {
        long files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql").length;
        Integer applied = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);

        assertThat(files).isGreaterThanOrEqualTo(9);
        assertThat(applied).isEqualTo((int) files);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void 성공_정가가_판매가보다_크거나_null이면_저장된다() {
        assertThatCode(() -> {
            insertProduct("c1", 1000, 1001);
            insertProduct("c2", 1000, null);
            insertProduct("c3", 0, 1);
        }).doesNotThrowAnyException();
    }

    @Test
    void 실패_정가가_판매가와_같거나_작으면_DB가_거부한다() {
        assertThatThrownBy(() -> insertProduct("c1", 1000, 1000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(message(t)).contains("products_original_price_gt_price"));
        assertThatThrownBy(() -> insertProduct("c2", 1000, 999))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(message(t)).contains("products_original_price_gt_price"));
    }

    @Test
    void 실패_음수_가격과_재고는_DB가_거부한다() {
        assertThatThrownBy(() -> insertProduct("c1", -1, null)).isInstanceOf(DataIntegrityViolationException.class);
        insertProduct("c2", 1, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET stock = -1 WHERE id = 'c2'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 성공_같은_사용자라도_다른_상품에는_리뷰를_쓸_수_있다() {
        data.product("p1", null, 1000, null, TestData.T0);
        data.product("p2", null, 1000, null, TestData.T0);
        String u1 = data.user();
        String u2 = data.user();

        assertThatCode(() -> {
            insertReview("r1", "p1", u1);
            insertReview("r2", "p2", u1);
            insertReview("r3", "p1", u2); // other user, same product
        }).doesNotThrowAnyException();
    }

    @Test
    void 성공_user_id가_NULL인_기존_익명_리뷰는_상품당_여러_개여도_된다() {
        data.product("p1", null, 1000, null, TestData.T0);

        assertThatCode(() -> {
            insertReview("r1", "p1", null);
            insertReview("r2", "p1", null);
            insertReview("r3", "p1", null);
        }).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviews", Integer.class)).isEqualTo(3);
    }

    @Test
    void 실패_같은_사용자가_같은_상품에_두_번째_리뷰를_쓰면_DB가_거부한다() {
        data.product("p1", null, 1000, null, TestData.T0);
        String u1 = data.user();
        insertReview("r1", "p1", u1);

        assertThatThrownBy(() -> insertReview("r2", "p1", u1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(message(t)).contains("uq_reviews_product_user"));
    }

    @Test
    void 성공_이메일이_NULL인_계정은_여러_개_만들_수_있다() {
        assertThatCode(() -> {
            insertUser("u1", "admin_one", null);
            insertUser("u2", "seller_one", null);
        }).doesNotThrowAnyException();
    }

    @Test
    void 실패_아이디나_이메일이_중복되면_DB가_거부한다() {
        insertUser("u1", "same_name", "a@example.com");

        assertThatThrownBy(() -> insertUser("u2", "same_name", "b@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(message(t)).contains("uq_users_username"));
        assertThatThrownBy(() -> insertUser("u3", "other_name", "a@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(message(t)).contains("uq_users_email"));
    }
}
