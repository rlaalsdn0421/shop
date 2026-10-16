package com.shop.backend.integration;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V17 product_images on a real Postgres: schema constraints and the createProduct / getImageUrls behavior, asserted on rows. */
class ProductImagesIntegrationTest extends PostgresIntegrationTest {

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_second_image ON product_images");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_second_image()");
    }

    private void insertProduct(String id) {
        jdbc.update("INSERT INTO products (id, name, description, price, image_url, stock, created_at) "
                        + "VALUES (?, 'n', 'd', 1000, 'http://legacy/' || ?, 1, ?)",
                id, id, Timestamp.valueOf(LocalDateTime.now()));
    }

    private void insertImage(String id, String productId, String url, int sortOrder) {
        jdbc.update("INSERT INTO product_images (id, product_id, url, sort_order) VALUES (?, ?, ?, ?)",
                id, productId, url, sortOrder);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static List<String> urls(int n) {
        return IntStream.range(0, n).mapToObj(i -> "https://img.example.com/" + i + ".png").toList();
    }

    private Product create(String legacyImageUrl, List<String> imageUrls) {
        return productService.createProduct("상품", "설명", 1000, legacyImageUrl, imageUrls, 5, null, null, null, null);
    }

    private List<String> rowUrls(String productId) {
        return jdbc.queryForList("SELECT url FROM product_images WHERE product_id = ? ORDER BY sort_order", String.class, productId);
    }

    // ---- 1. V17 schema ----

    @Test
    void 성공_product_images_테이블의_컬럼_타입과_인덱스가_V17대로_있다() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name || ':' || data_type || ':' || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable "
                        + "FROM information_schema.columns WHERE table_name = 'product_images' AND table_schema = current_schema()",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id:character varying:36:NO",
                "product_id:character varying:36:NO",
                "url:character varying:2000:NO",
                "sort_order:integer:-:NO");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_product_images_product_id' AND schemaname = current_schema()",
                String.class)).contains("product_images").contains("(product_id)");
    }

    @Test
    void 실패_같은_상품에_같은_sort_order를_넣으면_DB가_거부한다() {
        insertProduct("p1");
        insertImage("i1", "p1", "https://a/1", 0);

        assertThatThrownBy(() -> insertImage("i2", "p1", "https://a/2", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("product_images")).isEqualTo(1);
    }

    @Test
    void 성공_다른_상품이면_같은_sort_order를_넣을_수_있다() {
        insertProduct("p1");
        insertProduct("p2");

        assertThatCode(() -> {
            insertImage("i1", "p1", "https://a/1", 0);
            insertImage("i2", "p2", "https://a/2", 0);
        }).doesNotThrowAnyException();
        assertThat(count("product_images")).isEqualTo(2);
    }

    @Test
    void 실패_sort_order가_음수면_거부하고_0은_허용한다() {
        insertProduct("p1");

        assertThatThrownBy(() -> insertImage("i1", "p1", "https://a/1", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> insertImage("i2", "p1", "https://a/2", 0)).doesNotThrowAnyException();
        assertThat(count("product_images")).isEqualTo(1);
    }

    @Test
    void 실패_없는_상품_id나_NULL_url은_DB가_거부한다() {
        insertProduct("p1");

        assertThatThrownBy(() -> insertImage("i1", "no-such-product", "https://a/1", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertImage("i2", "p1", null, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("product_images")).isZero();
    }

    @Test
    void 성공_url은_2000자까지_저장되고_2001자는_DB가_거부한다() {
        insertProduct("p1");

        assertThatCode(() -> insertImage("i1", "p1", "h".repeat(2000), 0)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insertImage("i2", "p1", "h".repeat(2001), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("product_images")).isEqualTo(1);
    }

    // ---- 2. createProduct / getImageUrls ----

    @Test
    void 성공_imageUrls_3장이면_대표_이미지는_첫_장이고_행이_순서대로_3개_생긴다() {
        List<String> given = urls(3);

        Product saved = create("https://legacy.example.com/ignored.png", given);

        assertThat(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, saved.getId()))
                .isEqualTo(given.get(0)); // imageUrls wins over the legacy field
        assertThat(jdbc.queryForList("SELECT sort_order FROM product_images WHERE product_id = ? ORDER BY sort_order",
                Integer.class, saved.getId())).containsExactly(0, 1, 2);
        assertThat(rowUrls(saved.getId())).containsExactlyElementsOf(given);
        assertThat(productService.getImageUrls(saved)).containsExactlyElementsOf(given);
    }

    @Test
    void 성공_imageUrls_10장_경계값도_저장되고_순서대로_돌려준다() {
        List<String> given = urls(10);

        Product saved = create(null, given);

        assertThat(count("product_images")).isEqualTo(10);
        assertThat(productService.getImageUrls(saved)).containsExactlyElementsOf(given);
    }

    @Test
    void 성공_imageUrls_1장이면_행이_정확히_1개_생긴다() {
        Product saved = create(null, List.of("https://img.example.com/only.png"));

        assertThat(rowUrls(saved.getId())).containsExactly("https://img.example.com/only.png");
        assertThat(productService.getImageUrls(saved)).containsExactly("https://img.example.com/only.png");
    }

    @Test
    void 성공_옛_방식_imageUrl만_주면_product_images_행은_없고_imageUrl_한_장을_돌려준다() {
        Product saved = create("https://legacy.example.com/a.png", null);

        assertThat(count("product_images")).isZero();
        assertThat(productService.getImageUrls(saved)).containsExactly("https://legacy.example.com/a.png");
    }

    @Test
    void 성공_빈_imageUrls와_imageUrl이_함께_오면_imageUrl로_만들고_행은_없다() {
        Product saved = create("https://legacy.example.com/a.png", List.of());

        assertThat(count("product_images")).isZero();
        assertThat(productService.getImageUrls(saved)).containsExactly("https://legacy.example.com/a.png");
    }

    @Test
    void 성공_앞뒤_공백은_잘라서_저장하고_같은_URL이_두_번_있어도_각각_행이_생긴다() {
        Product saved = create(null, List.of("  https://img.example.com/a.png  ", "https://img.example.com/a.png"));

        assertThat(rowUrls(saved.getId())).containsExactly("https://img.example.com/a.png", "https://img.example.com/a.png");
    }

    @Test
    void 성공_url_길이_2000자는_저장되고_2001자는_거부된다() {
        String max = "https://" + "a".repeat(2000 - "https://".length());
        Product saved = create(null, List.of(max));
        assertThat(productService.getImageUrls(saved).get(0)).hasSize(2000);

        assertThatThrownBy(() -> create(null, List.of(max + "a"))).isInstanceOf(ValidationException.class);
        assertThat(count("products")).isEqualTo(1);
        assertThat(count("product_images")).isEqualTo(1);
    }

    // ---- 3. validation failures save nothing ----

    private void assertRejectedWithoutAnyRow(List<String> imageUrls) {
        assertThatThrownBy(() -> create(null, imageUrls)).isInstanceOf(ValidationException.class);
        assertThat(count("products")).isZero();
        assertThat(count("product_images")).isZero();
    }

    @Test
    void 실패_이미지가_11장이면_상품도_이미지도_저장되지_않는다() {
        assertRejectedWithoutAnyRow(urls(11));
    }

    @Test
    void 실패_공백뿐인_항목이_있으면_상품도_이미지도_저장되지_않는다() {
        assertRejectedWithoutAnyRow(List.of("https://img.example.com/a.png", "   "));
    }

    @Test
    void 실패_null_항목이_있으면_상품도_이미지도_저장되지_않는다() {
        List<String> withNull = new ArrayList<>(urls(1));
        withNull.add(null);
        assertRejectedWithoutAnyRow(withNull);
    }

    @Test
    void 실패_http_https가_아닌_항목이_있으면_상품도_이미지도_저장되지_않는다() {
        assertRejectedWithoutAnyRow(List.of("https://img.example.com/a.png", "javascript:alert(1)"));
        assertRejectedWithoutAnyRow(List.of("ftp://img.example.com/a.png"));
    }

    @Test
    void 실패_2001자_항목이_있으면_상품도_이미지도_저장되지_않는다() {
        assertRejectedWithoutAnyRow(List.of("https://img.example.com/a.png", "https://" + "a".repeat(2000)));
    }

    // ---- 4. atomicity ----

    @Test
    void 실패_두_번째_이미지_행_저장이_DB에서_실패하면_상품_행도_남지_않는다() {
        // DB-level failure on the 2nd image row, so the product row (inserted first) must be rolled back with it
        jdbc.execute("CREATE FUNCTION fail_second_image() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.sort_order = 1 THEN RAISE EXCEPTION 'forced image failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_second_image BEFORE INSERT ON product_images "
                + "FOR EACH ROW EXECUTE FUNCTION fail_second_image()");

        assertThatThrownBy(() -> create(null, urls(3))).isInstanceOf(RuntimeException.class);

        assertThat(count("products")).isZero();
        assertThat(count("product_images")).isZero(); // the sort_order 0 row is gone too
    }

    // ---- 5. ordering ----

    @Test
    void 성공_getImageUrls는_삽입_순서나_id_순서가_아니라_sort_order_순서로_돌려준다() {
        insertProduct("p1");
        // insertion order 2,0,1 and id order c-first would give 1,0,2 / 2,1,0; only sort_order gives 0,1,2
        insertImage("a-id", "p1", "https://img/two", 2);
        insertImage("c-id", "p1", "https://img/zero", 0);
        insertImage("b-id", "p1", "https://img/one", 1);
        Product p1 = productService.getProduct("p1").orElseThrow();

        assertThat(productService.getImageUrls(p1)).containsExactly("https://img/zero", "https://img/one", "https://img/two");
    }

    // ---- 6. legacy rows and isolation ----

    @Test
    void 성공_V17_이전에_만든_상품은_행이_없어도_image_url_한_장을_돌려준다() {
        insertProduct("old");
        Product old = productService.getProduct("old").orElseThrow();

        assertThat(count("product_images")).isZero();
        assertThat(productService.getImageUrls(old)).containsExactly("http://legacy/old");
    }

    @Test
    void 성공_두_상품의_이미지는_섞이지_않는다() {
        List<String> aUrls = List.of("https://a.example.com/1.png", "https://a.example.com/2.png");
        List<String> bUrls = List.of("https://b.example.com/1.png", "https://b.example.com/2.png", "https://b.example.com/3.png");
        Product a = create(null, aUrls);
        Product b = create(null, bUrls);
        insertProduct("legacy");
        Product legacy = productService.getProduct("legacy").orElseThrow();

        assertThat(productService.getImageUrls(a)).containsExactlyElementsOf(aUrls);
        assertThat(productService.getImageUrls(b)).containsExactlyElementsOf(bUrls);
        assertThat(productService.getImageUrls(legacy)).containsExactly("http://legacy/legacy");
        assertThat(count("product_images")).isEqualTo(5);
    }
}
