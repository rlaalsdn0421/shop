package com.shop.backend.integration;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.OptionEdit;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.StockChangedException;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admin product edit (ProductService.updateProduct) on a real Postgres: what lands in products / product_options /
 * product_images, the optimistic stock check with full rollback, and races with orders. Every "nothing changed"
 * assertion compares a dump of ALL rows of the three tables, so a partial save cannot slip through.
 */
class ProductEditIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OrderService orderService;

    @AfterEach
    void dropFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_second_image ON product_images");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_second_image()");
    }

    // ================================================================== helpers

    private static List<String> urls(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> "https://img.example.com/" + prefix + "-" + i + ".png").toList();
    }

    /** Registered through the real path: 3 images, category 신발, original price 2,000, one hashtag. */
    private String plain(int stock) {
        return productService.createProduct("옛이름", "옛설명", 1000, null, urls("old", 3), stock, "신발", 2000, List.of("옛태그"), null).getId();
    }

    /** Options S / M / L (sort_order 0..2) with the given prices and a stock of 10 each; returns the product id. */
    private String optionProduct(int... prices) {
        List<NewProductOption> options = new ArrayList<>();
        String[] sizes = {"S", "M", "L", "XL"};
        for (int i = 0; i < prices.length; i++) {
            options.add(new NewProductOption("화이트", null, sizes[i], prices[i], prices[i] + 500, 10));
        }
        return productService.createProduct("옛이름", "옛설명", null, null, urls("old", 2), null, "신발", null, List.of("옛태그"), options).getId();
    }

    private List<String> optionIds(String productId) {
        return jdbc.queryForList("SELECT id FROM product_options WHERE product_id = ? ORDER BY sort_order", String.class, productId);
    }

    private Product editPlain(String id, int price, Integer original, int stock, int expected, List<String> imageUrls) {
        return productService.updateProduct(id, "새이름", "새설명", "가방", List.of("a", "b"), imageUrls, price, original, stock,
                expected, null, "u1");
    }

    private Product editOptions(String id, List<String> imageUrls, OptionEdit... edits) {
        return productService.updateProduct(id, "새이름", "새설명", "가방", List.of("a", "b"), imageUrls, null, null, null, null,
                List.of(edits), "u1");
    }

    private static OptionEdit same(String optionId, int price, Integer original, int stock) {
        return new OptionEdit(optionId, price, original, stock, stock);
    }

    private Map<String, Object> productRow(String id) {
        return jdbc.queryForMap("SELECT name, description, price, original_price, image_url, stock, category, hashtags, price_varies "
                + "FROM products WHERE id = ?", id);
    }

    private List<Map<String, Object>> optionRows(String productId) {
        return jdbc.queryForList("SELECT id, color_name, color_hex, size_label, price, original_price, stock, sort_order "
                + "FROM product_options WHERE product_id = ? ORDER BY sort_order", productId);
    }

    private List<String> imageRows(String productId) {
        return jdbc.queryForList("SELECT url FROM product_images WHERE product_id = ? ORDER BY sort_order", String.class, productId);
    }

    private List<Integer> imageSortOrders(String productId) {
        return jdbc.queryForList("SELECT sort_order FROM product_images WHERE product_id = ? ORDER BY sort_order", Integer.class, productId);
    }

    private int stock(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private int optionStock(String id) {
        return jdbc.queryForObject("SELECT stock FROM product_options WHERE id = ?", Integer.class, id);
    }

    /** Every column of every row of the three tables, to compare "before" and "after". */
    private List<Object> dump() {
        return List.of(jdbc.queryForList("SELECT * FROM products ORDER BY id"),
                jdbc.queryForList("SELECT * FROM product_options ORDER BY id"),
                jdbc.queryForList("SELECT * FROM product_images ORDER BY id"));
    }

    /** Same, without one product (to prove the others were not touched). */
    private List<Object> dumpExcept(String productId) {
        return List.of(jdbc.queryForList("SELECT * FROM products WHERE id <> ? ORDER BY id", productId),
                jdbc.queryForList("SELECT * FROM product_options WHERE product_id <> ? ORDER BY id", productId),
                jdbc.queryForList("SELECT * FROM product_images WHERE product_id <> ? ORDER BY id", productId));
    }

    private void order(OrderLine... lines) {
        orderService.createOrder(data.user(), UUID.randomUUID().toString(), "구매자", "010-0000-0000", "서울", List.of(lines));
    }

    // ================================================================== plain product

    @Test
    void 성공_옵션_없는_상품의_모든_수정_필드가_저장되고_이미지는_목록_순서로_다시_쓰인다() {
        String id = plain(10);
        String other = plain(5);
        List<Object> othersBefore = dumpExcept(id);
        List<String> images = urls("new", 2);

        Product result = editPlain(id, 12000, 15000, 8, 10, images);

        assertThat(result.getId()).isEqualTo(id);
        Map<String, Object> row = productRow(id);
        assertThat(row).containsEntry("name", "새이름").containsEntry("description", "새설명").containsEntry("price", 12000)
                .containsEntry("original_price", 15000).containsEntry("stock", 8).containsEntry("category", "가방")
                .containsEntry("hashtags", "a b").containsEntry("price_varies", false)
                .containsEntry("image_url", images.get(0));
        assertThat(imageRows(id)).containsExactlyElementsOf(images);
        assertThat(imageSortOrders(id)).containsExactly(0, 1);
        assertThat(dumpExcept(id)).as("other product untouched").isEqualTo(othersBefore);
        assertThat(productRow(other)).containsEntry("name", "옛이름");
    }

    @Test
    void 성공_정가_카테고리_해시태그를_비우면_null로_저장된다() {
        String id = plain(10);

        productService.updateProduct(id, "새이름", "새설명", " ", null, urls("new", 1), 1000, null, 10, 10, null, "u1");

        assertThat(productRow(id)).containsEntry("original_price", null).containsEntry("category", null)
                .containsEntry("hashtags", null);
    }

    @Test
    void 성공_이미지를_늘리고_줄이고_순서만_바꿔도_행이_목록과_같고_sort_order_충돌이_없다() {
        String id = plain(10);
        List<String> ten = urls("ten", 10);
        List<String> reversed = new ArrayList<>(ten);
        java.util.Collections.reverse(reversed);

        for (List<String> step : List.of(ten, urls("one", 1), reversed, urls("one", 1), urls("two", 2), urls("two", 2))) {
            editPlain(id, 1000, null, 10, 10, step);

            assertThat(imageRows(id)).containsExactlyElementsOf(step);
            assertThat(imageSortOrders(id)).isEqualTo(IntStream.range(0, step.size()).boxed().toList());
            assertThat(productRow(id)).containsEntry("image_url", step.get(0));
        }
    }

    @Test
    void 성공_이미지_행이_없던_옛_상품도_수정하면_행이_생기고_대표_이미지가_바뀐다() {
        data.product("legacy", null, 1000, null, TestData.T0);
        assertThat(imageRows("legacy")).isEmpty();

        editPlain("legacy", 1000, null, 100, 100, urls("new", 3));

        assertThat(imageRows("legacy")).containsExactlyElementsOf(urls("new", 3));
        assertThat(productRow("legacy")).containsEntry("image_url", urls("new", 3).get(0));
    }

    @Test
    void 성공_재고를_바꾸면_입력한_숫자가_저장되고_0도_저장된다() {
        String id = plain(10);

        editPlain(id, 1000, null, 3, 10, urls("new", 1));
        assertThat(stock(id)).isEqualTo(3);

        editPlain(id, 1000, null, 0, 3, urls("new", 1));
        assertThat(stock(id)).isZero();

        editPlain(id, 1000, null, 7, 0, urls("new", 1)); // 0 as the expected value is a real value too
        assertThat(stock(id)).isEqualTo(7);
    }

    @Test
    void 성공_재고를_바꾸지_않았으면_그_사이_팔린_재고를_덮어쓰지_않고_다른_필드는_저장된다() {
        String id = plain(10);
        jdbc.update("UPDATE products SET stock = 7 WHERE id = ?", id); // an order took 3 after the admin loaded 10

        editPlain(id, 1500, null, 10, 10, urls("new", 2));

        assertThat(stock(id)).as("not resurrected to 10").isEqualTo(7);
        assertThat(productRow(id)).containsEntry("price", 1500).containsEntry("name", "새이름");
        assertThat(imageRows(id)).containsExactlyElementsOf(urls("new", 2));
    }

    @Test
    void 실패_재고가_그_사이_바뀌었으면_409용_예외이고_이름_가격_이미지_재고_모두_그대로다() {
        String id = plain(10);
        jdbc.update("UPDATE products SET stock = 7 WHERE id = ?", id);
        List<Object> before = dump();

        assertThatThrownBy(() -> editPlain(id, 1500, 9000, 20, 10, urls("new", 2))).isInstanceOf(StockChangedException.class);

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_입력한_숫자가_현재_재고와_같아도_화면이_본_값과_다르면_충돌이다() {
        String id = plain(10);
        jdbc.update("UPDATE products SET stock = 7 WHERE id = ?", id);
        List<Object> before = dump();

        assertThatThrownBy(() -> editPlain(id, 1000, null, 7, 10, urls("new", 1))).isInstanceOf(StockChangedException.class);

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_없는_상품은_상품_없음이고_아무_행도_생기지_않는다() {
        String other = plain(10);
        List<Object> before = dump();

        assertThatThrownBy(() -> editPlain("missing", 1000, null, 10, 10, urls("new", 1))).isInstanceOf(ProductNotFoundException.class);

        assertThat(dump()).isEqualTo(before);
        assertThat(imageRows("missing")).isEmpty();
        assertThat(other).isNotNull();
    }

    @Test
    void 실패_검증_오류는_재고_충돌보다_먼저이고_아무것도_바뀌지_않는다() {
        String id = plain(10);
        jdbc.update("UPDATE products SET stock = 7 WHERE id = ?", id);
        List<Object> before = dump();

        // stale stock (would be 409) together with: bad price, bad original price, no images, bad category, no expectedStock
        assertThatThrownBy(() -> editPlain(id, -1, null, 20, 10, urls("new", 1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editPlain(id, 1000, 1000, 20, 10, urls("new", 1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editPlain(id, 1000, null, 20, 10, List.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editPlain(id, 1000, null, 20, 10, urls("new", 11))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", "없는분류", null, urls("new", 1), 1000, null, 20, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, urls("new", 1), 1000, null, 20, null, null, "u1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, urls("new", 1), 1000, null, -1, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_옵션_없는_상품에_옵션을_같이_보내면_검증_오류이고_그대로다() {
        String id = plain(10);
        List<Object> before = dump();

        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, urls("new", 1), 1000, null, 10, 10,
                List.of(same("x", 1000, null, 1)), "u1")).isInstanceOf(ValidationException.class);

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 성공_재고_100만은_저장되고_100만1은_충돌보다_먼저_검증_오류이며_아무것도_바뀌지_않는다() {
        String id = plain(10);
        jdbc.update("UPDATE products SET stock = 7 WHERE id = ?", id); // stale too: 400 must still win
        List<Object> before = dump();

        assertThatThrownBy(() -> editPlain(id, 1500, null, 1_000_001, 10, urls("new", 2)))
                .isInstanceOf(ValidationException.class).hasMessage("재고 수량은 1,000,000개 이하로 입력해 주세요.");
        assertThat(dump()).isEqualTo(before);

        jdbc.update("UPDATE products SET stock = 10 WHERE id = ?", id);
        editPlain(id, 1500, null, 1_000_000, 10, urls("new", 2));
        assertThat(stock(id)).isEqualTo(1_000_000);
        editPlain(id, 1600, null, 1_000_000, 1_000_000, urls("new", 2)); // unchanged at the cap is fine
        assertThat(stock(id)).isEqualTo(1_000_000);
    }

    @Test
    void 성공_옵션_재고_100만은_저장되고_100만1이나_옵션_51개는_검증_오류이며_아무것도_바뀌지_않는다() {
        String id = optionProduct(1000, 2000);
        List<String> ids = optionIds(id);
        List<Object> before = dump();

        assertThatThrownBy(() -> editOptions(id, urls("new", 1), new OptionEdit(ids.get(0), 1000, null, 1_000_001, 10),
                same(ids.get(1), 2000, null, 10))).isInstanceOf(ValidationException.class);
        List<OptionEdit> tooMany = IntStream.range(0, 51).mapToObj(i -> same("x" + i, 1000, null, 1)).toList();
        assertThatThrownBy(() -> editOptions(id, urls("new", 1), tooMany.toArray(OptionEdit[]::new)))
                .isInstanceOf(ValidationException.class).hasMessage("옵션은 1~50개까지 등록할 수 있어요.");
        assertThat(dump()).isEqualTo(before);

        editOptions(id, urls("new", 1), new OptionEdit(ids.get(0), 1000, null, 1_000_000, 10), same(ids.get(1), 2000, null, 10));
        assertThat(optionStock(ids.get(0))).isEqualTo(1_000_000);
    }

    @Test
    void 성공_상품_등록도_재고_100만까지만_저장되고_100만1이면_행이_생기지_않는다() {
        assertThatThrownBy(() -> productService.createProduct("n", "d", 1000, null, urls("c", 1), 1_000_001, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.createProduct("n", "d", null, null, urls("c", 1), null, null, null, null,
                List.of(new NewProductOption("화이트", null, "M", 1000, null, 1_000_001))))
                .isInstanceOf(ValidationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_options", Integer.class)).isZero();

        String plainId = productService.createProduct("n", "d", 1000, null, urls("c", 1), 1_000_000, null, null, null, null).getId();
        String optionId = productService.createProduct("n", "d", null, null, urls("c", 1), null, null, null, null,
                List.of(new NewProductOption("화이트", null, "M", 1000, null, 1_000_000))).getId();
        assertThat(stock(plainId)).isEqualTo(1_000_000);
        assertThat(optionStock(optionIds(optionId).get(0))).isEqualTo(1_000_000);
    }

    @Test
    void 실패_없는_상품이어도_본문이_틀리면_상품_없음이_아니라_검증_오류다() {
        plain(10);
        List<Object> before = dump();

        assertThatThrownBy(() -> productService.updateProduct("missing", " ", "새설명", null, null, urls("new", 1), 1000, null, 10, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct("missing", "n", "새설명", "없는분류", null, urls("new", 1), 1000, null, 10, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct("missing", "n", "새설명", null, null, List.of(), 1000, null, 10, 10, null, "u1"))
                .isInstanceOf(ValidationException.class);
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 성공_본문이_틀린_수정은_다른_트랜잭션이_상품_행을_잡고_있어도_기다리지_않고_바로_검증_오류다() throws Exception {
        String id = plain(10);
        List<Object> before = dump();
        Throwable[] failure = new Throwable[1];
        long[] elapsed = new long[1];

        holdingRowLock("products", id, () -> {
            long start = System.nanoTime();
            failure[0] = failureOf(() -> productService.updateProduct(id, " ", "새설명", null, null, urls("new", 1), 1500, null, 5, 10, null, "u1"), 15);
            elapsed[0] = (System.nanoTime() - start) / 1_000_000;
        });

        assertThat(failure[0]).isInstanceOf(ValidationException.class);
        assertThat(elapsed[0]).as("no lock wait (lock_timeout is 3 s)").isLessThan(2_000L);
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_이미지_저장이_DB에서_중간에_실패하면_이름_재고_이미지_삭제까지_모두_되돌려진다() {
        String id = plain(10);
        List<Object> before = dump();
        jdbc.execute("CREATE FUNCTION fail_second_image() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.sort_order = 1 THEN RAISE EXCEPTION 'forced image failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_fail_second_image BEFORE INSERT ON product_images "
                + "FOR EACH ROW EXECUTE FUNCTION fail_second_image()");

        assertThatThrownBy(() -> editPlain(id, 1500, null, 5, 10, urls("new", 3))).isInstanceOf(RuntimeException.class);

        assertThat(dump()).isEqualTo(before);
    }

    // ================================================================== product with options

    @Test
    void 성공_옵션_상품은_옵션별_값이_저장되고_상품_가격은_가장_싼_옵션이며_이름_순서는_그대로다() {
        String id = optionProduct(2000, 3000, 4000);
        String other = optionProduct(1000, 1000);
        List<String> ids = optionIds(id);
        List<Map<String, Object>> before = optionRows(id);
        List<Object> othersBefore = dumpExcept(id);
        List<String> images = urls("new", 3);

        editOptions(id, images,
                new OptionEdit(ids.get(2), 5000, null, 0, 10),
                new OptionEdit(ids.get(0), 3000, 4000, 12, 10),
                new OptionEdit(ids.get(1), 2000, 2500, 10, 10));

        List<Map<String, Object>> after = optionRows(id);
        assertThat(after).extracting(r -> r.get("id")).containsExactlyElementsOf(ids);
        assertThat(after).extracting(r -> r.get("price")).containsExactly(3000, 2000, 5000);
        assertThat(after).extracting(r -> r.get("original_price")).containsExactly(4000, 2500, null);
        assertThat(after).extracting(r -> r.get("stock")).containsExactly(12, 10, 0);
        for (int i = 0; i < 3; i++) { // names, hex, order never change
            assertThat(after.get(i)).containsEntry("color_name", before.get(i).get("color_name"))
                    .containsEntry("color_hex", before.get(i).get("color_hex"))
                    .containsEntry("size_label", before.get(i).get("size_label"))
                    .containsEntry("sort_order", before.get(i).get("sort_order"));
        }
        assertThat(productRow(id)).containsEntry("price", 2000).containsEntry("original_price", 2500)
                .containsEntry("price_varies", true).containsEntry("stock", 0).containsEntry("name", "새이름")
                .containsEntry("image_url", images.get(0));
        assertThat(imageRows(id)).containsExactlyElementsOf(images);
        assertThat(dumpExcept(id)).as("other products untouched").isEqualTo(othersBefore);
        assertThat(other).isNotEqualTo(id);
    }

    @Test
    void 성공_가장_싼_가격이_같으면_앞선_옵션의_정가가_상품에_쓰이고_가격이_모두_같으면_price_varies가_false다() {
        String id = optionProduct(1000, 2000, 3000);
        assertThat(productRow(id)).containsEntry("price_varies", true);
        List<String> ids = optionIds(id);

        // request in reverse order: "first" means sort_order, not request order
        editOptions(id, urls("new", 1), same(ids.get(2), 1000, null, 10), same(ids.get(1), 1000, 3000, 10),
                same(ids.get(0), 1000, 1500, 10));

        assertThat(productRow(id)).containsEntry("price", 1000).containsEntry("original_price", 1500)
                .containsEntry("price_varies", false).containsEntry("stock", 0);

        // back to different prices: recomputed to true again, and the cheapest is a later option this time
        editOptions(id, urls("new", 1), same(ids.get(0), 4000, null, 10), same(ids.get(1), 3000, 3500, 10),
                same(ids.get(2), 3000, 9000, 10));

        assertThat(productRow(id)).containsEntry("price", 3000).containsEntry("original_price", 3500)
                .containsEntry("price_varies", true);
    }

    @Test
    void 성공_가격이_하나만_다르면_price_varies가_true이고_옵션이_하나뿐이면_false다() {
        String id = optionProduct(1000, 1000, 1000);
        assertThat(productRow(id)).containsEntry("price_varies", false);
        List<String> ids = optionIds(id);

        editOptions(id, urls("new", 1), same(ids.get(0), 1000, null, 10), same(ids.get(1), 1000, null, 10),
                same(ids.get(2), 1001, null, 10));

        assertThat(productRow(id)).containsEntry("price", 1000).containsEntry("price_varies", true);

        String single = optionProduct(700);
        editOptions(single, urls("new", 1), same(optionIds(single).get(0), 900, null, 10));
        assertThat(productRow(single)).containsEntry("price", 900).containsEntry("price_varies", false).containsEntry("stock", 0);
    }

    @Test
    void 성공_재고를_바꾸지_않은_옵션은_그_사이_팔린_재고를_유지하고_바꾼_옵션은_입력값이_저장된다() {
        String id = optionProduct(1000, 2000);
        List<String> ids = optionIds(id);
        jdbc.update("UPDATE product_options SET stock = 6 WHERE id = ?", ids.get(0)); // sold 4 of the 10 the admin saw

        editOptions(id, urls("new", 1), same(ids.get(0), 1500, null, 10), new OptionEdit(ids.get(1), 2500, null, 25, 10));

        assertThat(optionStock(ids.get(0))).as("not resurrected").isEqualTo(6);
        assertThat(optionStock(ids.get(1))).isEqualTo(25);
        assertThat(optionRows(id)).extracting(r -> r.get("price")).containsExactly(1500, 2500);
        assertThat(productRow(id)).containsEntry("price", 1500);
    }

    @Test
    void 실패_옵션_하나의_재고가_그_사이_바뀌면_409용_예외이고_다른_옵션_상품_이미지도_그대로다() {
        // whichever option is the stale one, the others (possibly locked and changed earlier) must be rolled back too
        for (int stale = 0; stale < 3; stale++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            String id = optionProduct(1000, 2000, 3000);
            List<String> ids = optionIds(id);
            jdbc.update("UPDATE product_options SET stock = 4 WHERE id = ?", ids.get(stale));
            List<Object> before = dump();

            OptionEdit[] edits = IntStream.range(0, 3)
                    .mapToObj(i -> new OptionEdit(ids.get(i), 100 + i, null, 50, 10))
                    .toArray(OptionEdit[]::new);
            // the other options are really at 10 (as expected) but the admin typed 50 for them: they WOULD change
            assertThatThrownBy(() -> editOptions(id, urls("new", 2), edits)).as("stale option %d", stale)
                    .isInstanceOf(StockChangedException.class);

            assertThat(dump()).as("stale option %d: full rollback", stale).isEqualTo(before);
        }
    }

    @Test
    void 실패_옵션_목록이_틀리거나_값이_틀리면_검증_오류이고_아무것도_바뀌지_않는다() {
        String id = optionProduct(1000, 2000);
        String other = optionProduct(500);
        List<String> ids = optionIds(id);
        String foreign = optionIds(other).get(0);
        jdbc.update("UPDATE product_options SET stock = 4 WHERE id = ?", ids.get(0)); // stale, so a 409 would show if 400 did not win
        List<Object> before = dump();
        List<String> images = urls("new", 1);

        // missing / unknown / foreign / duplicate / null id
        assertThatThrownBy(() -> editOptions(id, images, same(ids.get(0), 1000, null, 10))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, same(ids.get(0), 1000, null, 10), same("nope", 1000, null, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, same(ids.get(0), 1000, null, 10), same(foreign, 1000, null, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, same(ids.get(0), 1000, null, 10), same(ids.get(0), 1000, null, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, same(ids.get(0), 1000, null, 10), same(null, 1000, null, 10)))
                .isInstanceOf(ValidationException.class);
        // bad numbers on the second option while the first one is stale
        assertThatThrownBy(() -> editOptions(id, images, new OptionEdit(ids.get(0), 1000, null, 50, 10), new OptionEdit(ids.get(1), -1, null, 10, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, new OptionEdit(ids.get(0), 1000, null, 50, 10), new OptionEdit(ids.get(1), 2000, 2000, 10, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, new OptionEdit(ids.get(0), 1000, null, 50, 10), new OptionEdit(ids.get(1), 2000, null, -1, 10)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> editOptions(id, images, new OptionEdit(ids.get(0), 1000, null, 50, 10), new OptionEdit(ids.get(1), 2000, null, 10, null)))
                .isInstanceOf(ValidationException.class);
        // top-level price/stock/expectedStock are not allowed for a product with options
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, images, 1000, null, null, null,
                List.of(same(ids.get(0), 1000, null, 10), same(ids.get(1), 2000, null, 10)), "u1")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, images, null, null, 5, 5,
                List.of(same(ids.get(0), 1000, null, 10), same(ids.get(1), 2000, null, 10)), "u1")).isInstanceOf(ValidationException.class);
        // no options at all
        assertThatThrownBy(() -> productService.updateProduct(id, "새이름", "새설명", null, null, images, null, null, null, null, null, "u1"))
                .isInstanceOf(ValidationException.class);
        // bad shared field with valid options
        assertThatThrownBy(() -> productService.updateProduct(id, " ", "새설명", null, null, images, null, null, null, null,
                List.of(same(ids.get(0), 1000, null, 4), same(ids.get(1), 2000, null, 10)), "u1")).isInstanceOf(ValidationException.class);

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_옵션_가격이_정가_이상이면_옵션_정가_제약_전에_검증_오류로_막힌다() {
        String id = optionProduct(1000);
        List<Object> before = dump();

        assertThatThrownBy(() -> editOptions(id, urls("new", 1), same(optionIds(id).get(0), 1000, 1000, 10)))
                .isInstanceOf(ValidationException.class);

        assertThat(dump()).isEqualTo(before);
    }

    // ================================================================== locks

    private interface Body {
        void run() throws Exception;
    }

    /** Holds a FOR UPDATE row lock in a separate open transaction while {@code body} runs; always rolled back after. */
    private void holdingRowLock(String table, String id, Body body) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT 1 FROM " + table + " WHERE id = '" + id + "' FOR UPDATE");
                }
                body.run();
            } finally {
                holder.rollback();
            }
        }
    }

    private static Throwable failureOf(Runnable task, int seconds) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(task).get(seconds, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 실패_상품_행_잠금이_풀리지_않으면_3초_뒤_잠금_예외로_끝나고_아무것도_바뀌지_않는다() throws Exception {
        String id = plain(10);
        List<Object> before = dump();
        Throwable[] failure = new Throwable[1];
        long[] elapsed = new long[1];

        holdingRowLock("products", id, () -> {
            long start = System.nanoTime();
            failure[0] = failureOf(() -> editPlain(id, 1500, null, 5, 10, urls("new", 2)), 15);
            elapsed[0] = (System.nanoTime() - start) / 1_000_000;
        });

        assertThat(failure[0]).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(elapsed[0]).as("waited about lock_timeout (3 s)").isBetween(2_000L, 9_000L);
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_옵션_행_잠금이_풀리지_않으면_3초_뒤_잠금_예외로_끝나고_상품도_다른_옵션도_그대로다() throws Exception {
        String id = optionProduct(1000, 2000);
        List<String> ids = optionIds(id);
        List<Object> before = dump();
        String blocked = ids.stream().sorted().toList().get(1); // locked second, so the first one was already changed in memory
        Throwable[] failure = new Throwable[1];

        holdingRowLock("product_options", blocked, () -> failure[0] = failureOf(
                () -> editOptions(id, urls("new", 2), new OptionEdit(ids.get(0), 1100, null, 50, 10),
                        new OptionEdit(ids.get(1), 2100, null, 50, 10)), 15));

        assertThat(failure[0]).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 성공_잠금이_없으면_같은_수정이_바로_성공한다() {
        String id = plain(10);

        editPlain(id, 1500, null, 5, 10, urls("new", 2));

        assertThat(stock(id)).isEqualTo(5);
    }

    // ================================================================== concurrency

    /** Runs all tasks at the same instant; returns what each threw (null = success). */
    private static List<Throwable> runTogether(List<Runnable> tasks) throws Exception {
        CyclicBarrier start = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        task.run();
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS)); // a hang fails the test here
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 성공_같은_재고를_보고_동시에_저장하면_한_명만_성공하고_나머지는_충돌이며_재고는_그_한_명의_숫자다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            String id = plain(10);
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int typed = 20 + i;
                tasks.add(() -> editPlain(id, 1000 + typed, null, typed, 10, urls("new", 2)));
            }

            List<Throwable> results = runTogether(tasks);

            assertThat(results.stream().filter(r -> r == null).count()).as("round %d: winners", round).isEqualTo(1);
            assertThat(results.stream().filter(r -> r != null)).as("round %d: others", round)
                    .allMatch(r -> r instanceof StockChangedException);
            int winnerTyped = stock(id);
            assertThat(winnerTyped).isBetween(20, 24);
            assertThat(productRow(id)).as("price and stock come from the same winner").containsEntry("price", 1000 + winnerTyped);
        }
    }

    @Test
    void 성공_재고를_새로_입력하는_수정과_주문이_동시에_와도_팔린_재고가_되살아나지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            String id = plain(1000);
            List<Runnable> tasks = new ArrayList<>();
            tasks.add(() -> editPlain(id, 1500, null, 500, 1000, urls("new", 2)));
            for (int i = 0; i < 5; i++) {
                tasks.add(() -> order(new OrderLine(id, 1)));
            }

            List<Throwable> results = runTogether(tasks);

            Throwable edit = results.get(0);
            assertThat(results.subList(1, results.size())).as("round %d: orders", round).allMatch(r -> r == null);
            if (edit == null) {
                // edit committed first (then some orders followed) or after some orders... the latter would have been a conflict
                assertThat(stock(id)).as("round %d: edit won", round).isBetween(495, 500);
                assertThat(productRow(id)).containsEntry("price", 1500);
            } else {
                assertThat(edit).as("round %d", round).isInstanceOf(StockChangedException.class);
                assertThat(stock(id)).as("round %d: edit lost, only the orders counted", round).isEqualTo(995);
                assertThat(productRow(id)).containsEntry("price", 1000);
            }
        }
    }

    @Test
    void 성공_재고를_안_바꾸는_수정과_주문이_동시에_돌아도_모두_성공하고_재고는_주문만큼만_줄어든다() throws Exception {
        String id = plain(1000);
        int rounds = 20;
        int ordersPerRound = 4;
        for (int round = 0; round < rounds; round++) {
            int price = 1000 + round;
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> editPlain(id, price, null, 1000, 1000, urls("r" + price, 1 + price % 3)));
            }
            for (int i = 0; i < ordersPerRound; i++) {
                tasks.add(() -> order(new OrderLine(id, 1)));
            }

            assertThat(runTogether(tasks)).as("round %d", round).allMatch(r -> r == null);
        }

        assertThat(stock(id)).isEqualTo(1000 - rounds * ordersPerRound);
        assertThat(imageSortOrders(id)).isEqualTo(IntStream.range(0, imageSortOrders(id).size()).boxed().toList());
    }

    /**
     * An order locks its option rows first (decrement) and only at the flush inserts order_items, whose FK check wants a
     * share lock on the PRODUCT row; the edit locks the product row first and then the option rows. A trigger holds the
     * order inside that window so the interleaving happens every time instead of by luck. It only works out because
     * Hibernate's PESSIMISTIC_WRITE on Postgres is FOR NO KEY UPDATE, which does not conflict with that FK share lock; a
     * plain FOR UPDATE on the product row would deadlock here (and this test would catch it).
     */
    @Test
    void 성공_주문이_옵션_행을_잡은_채_상품_행을_기다리는_틈에_수정이_와도_교착_없이_둘_다_성공한다() throws Exception {
        String id = optionProduct(1000, 2000);
        List<String> ids = optionIds(id);
        jdbc.execute("CREATE FUNCTION slow_order_item() RETURNS trigger AS $$ BEGIN PERFORM pg_sleep(1.0); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_slow_order_item BEFORE INSERT ON order_items FOR EACH ROW EXECUTE FUNCTION slow_order_item()");
        try {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> order = pool.submit(() -> order(new OrderLine(id, 1, ids.get(0)), new OrderLine(id, 1, ids.get(1))));
                Thread.sleep(300); // the order now holds both option rows and sleeps in the trigger
                Future<?> edit = pool.submit(() -> editOptions(id, urls("new", 1), same(ids.get(0), 1500, null, 10),
                        same(ids.get(1), 2500, null, 10)));
                Throwable orderFailure = outcome(order);
                Throwable editFailure = outcome(edit);

                assertThat(orderFailure).as("order").isNull();
                assertThat(editFailure).as("edit").isNull();
            } finally {
                pool.shutdownNow();
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_slow_order_item ON order_items");
            jdbc.execute("DROP FUNCTION IF EXISTS slow_order_item()");
        }
        assertThat(optionStock(ids.get(0))).isEqualTo(9);
        assertThat(optionStock(ids.get(1))).isEqualTo(9);
        assertThat(productRow(id)).containsEntry("price", 1500);
    }

    private static Throwable outcome(Future<?> future) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    @Test
    void 성공_옵션_상품의_수정과_반대_순서_옵션_주문이_동시에_와도_교착_없이_끝나고_재고와_가격이_일관된다() throws Exception {
        String id = optionProduct(1000, 2000, 3000);
        List<String> ids = optionIds(id);
        jdbc.update("UPDATE product_options SET stock = 1000 WHERE product_id = ?", id);
        int rounds = 20;
        int ordersPerRound = 4;
        for (int round = 0; round < rounds; round++) {
            int base = 1000 + round * 10;
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                tasks.add(() -> editOptions(id, urls("new", 2), same(ids.get(0), base, null, 1000),
                        same(ids.get(1), base + 1000, null, 1000), same(ids.get(2), base + 2000, null, 1000)));
            }
            for (int i = 0; i < ordersPerRound / 2; i++) {
                tasks.add(() -> order(new OrderLine(id, 1, ids.get(0)), new OrderLine(id, 1, ids.get(2))));
                tasks.add(() -> order(new OrderLine(id, 1, ids.get(2)), new OrderLine(id, 1, ids.get(0))));
            }

            assertThat(runTogether(tasks)).as("round %d", round).allMatch(r -> r == null);
        }

        int sold = rounds * ordersPerRound; // each order takes 1 of option 0 and 1 of option 2
        assertThat(optionStock(ids.get(0))).isEqualTo(1000 - sold);
        assertThat(optionStock(ids.get(1))).isEqualTo(1000);
        assertThat(optionStock(ids.get(2))).isEqualTo(1000 - sold);
        List<Map<String, Object>> options = optionRows(id);
        int cheapest = options.stream().mapToInt(r -> (Integer) r.get("price")).min().orElseThrow();
        assertThat(productRow(id)).containsEntry("price", cheapest).containsEntry("price_varies", true).containsEntry("stock", 0);
        assertThat((Integer) options.get(1).get("price") - cheapest).isEqualTo(1000);
        assertThat((Integer) options.get(2).get("price") - cheapest).isEqualTo(2000);
    }
}
