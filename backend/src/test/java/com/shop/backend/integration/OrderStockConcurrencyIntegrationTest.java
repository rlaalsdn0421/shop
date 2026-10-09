package com.shop.backend.integration;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stock must never be oversold. Real Postgres, real separate transactions per thread (no surrounding test
 * transaction), all threads released by one barrier. The V1 CHECK (stock >= 0) cannot catch a lost update, so the
 * order flow itself has to be atomic.
 */
class OrderStockConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OrderService orderService;

    private static OrderLine line(String productId, int quantity) {
        return new OrderLine(productId, quantity);
    }

    private void order(OrderLine... lines) {
        orderService.createOrder("구매자", "010-0000-0000", "서울", List.of(lines));
    }

    private void product(String id, int stock) {
        product(id, stock, 1000);
    }

    private void product(String id, int stock, int price) {
        data.product(id, null, price, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    private int stock(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    /** Runs all tasks at the same instant; returns the thrown InsufficientStockException (or null on success) per task. */
    private List<InsufficientStockException> runTogether(List<Runnable> tasks) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<InsufficientStockException>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                Callable<InsufficientStockException> call = () -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        task.run();
                        return null;
                    } catch (InsufficientStockException ex) {
                        return ex;
                    }
                };
                futures.add(pool.submit(call));
            }
            List<InsufficientStockException> results = new ArrayList<>();
            for (Future<InsufficientStockException> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS)); // deadlock/timeouts/other errors fail the test here
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long failures(List<InsufficientStockException> results) {
        return results.stream().filter(r -> r != null).count();
    }

    private static List<Runnable> same(int threads, Runnable task) {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return tasks;
    }

    @Test
    void 성공_마지막_1개를_6명이_동시에_주문하면_한_명만_성공한다() throws Exception {
        for (int round = 0; round < 10; round++) { // several rounds: the race is timing dependent
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 1);

            List<InsufficientStockException> results = runTogether(same(6, () -> order(line("p1", 1))));

            assertThat(failures(results)).as("round %d: failed orders", round).isEqualTo(5);
            assertThat(stock("p1")).as("round %d: stock", round).isZero();
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(1);
            assertThat(count("order_items")).as("round %d: order items", round).isEqualTo(1);
        }
    }

    @Test
    void 성공_재고_5개를_6명이_동시에_1개씩_주문하면_5명만_성공한다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 5);

            List<InsufficientStockException> results = runTogether(same(6, () -> order(line("p1", 1))));

            assertThat(failures(results)).as("round %d: failed orders", round).isEqualTo(1);
            assertThat(stock("p1")).as("round %d: stock", round).isZero();
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(5);
        }
    }

    @Test
    void 성공_재고_10개를_3명이_4개씩_동시에_주문하면_2명만_성공하고_2개가_남는다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 10);

            List<InsufficientStockException> results = runTogether(same(3, () -> order(line("p1", 4))));

            assertThat(failures(results)).as("round %d: failed orders", round).isEqualTo(1);
            assertThat(stock("p1")).as("round %d: stock", round).isEqualTo(2);
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(2);
        }
    }

    @Test
    void 성공_같은_상품이_여러_줄이어도_합산해서_동시에도_초과_판매되지_않는다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 3);

            // each order wants 1 + 1 = 2 units of p1 -> only one of the three fits into 3
            List<InsufficientStockException> results =
                    runTogether(same(3, () -> order(line("p1", 1), line("p1", 1))));

            assertThat(failures(results)).as("round %d: failed orders", round).isEqualTo(2);
            assertThat(stock("p1")).as("round %d: stock", round).isEqualTo(1);
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(1);
        }
    }

    @Test
    void 실패_한_상품의_재고가_부족하면_다른_상품의_재고도_차감되지_않고_주문도_남지_않는다() {
        product("pA", 5);
        product("pB", 1);

        assertThatThrownBy(() -> order(line("pA", 2), line("pB", 2))).isInstanceOf(InsufficientStockException.class);
        assertThat(stock("pA")).isEqualTo(5);
        assertThat(stock("pB")).isEqualTo(1);

        // the product that sorts FIRST is the one that is short
        product("pC", 1);
        product("pD", 5);
        assertThatThrownBy(() -> order(line("pD", 2), line("pC", 2))).isInstanceOf(InsufficientStockException.class);
        assertThat(stock("pC")).isEqualTo(1);
        assertThat(stock("pD")).isEqualTo(5);

        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
    }

    /**
     * The sold-out pre-check hides this path in the normal case, so the race is forced: a test-only trigger empties pB
     * while pA is being updated. pA was already decremented when pB's update fails, and that must be undone.
     */
    @Test
    void 실패_다른_상품의_갱신이_뒤늦게_실패하면_앞서_차감한_상품도_함께_되돌려진다() throws Exception {
        product("pA", 5);
        product("pB", 5);

        withProductsUpdateTrigger("BEGIN IF NEW.id = 'pA' THEN UPDATE products SET stock = 0 WHERE id = 'pB'; END IF; RETURN NEW; END;",
                () -> assertThatThrownBy(() -> order(line("pA", 1), line("pB", 1))).isInstanceOf(InsufficientStockException.class));

        assertThat(stock("pA")).isEqualTo(5);
        assertThat(stock("pB")).isEqualTo(5);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
    }

    @Test
    void 성공_주문_총액과_상품별_가격_스냅샷이_정확히_저장된다() {
        product("pA", 10, 1500);
        product("pB", 10, 700);

        order(line("pB", 3), line("pA", 2));
        jdbc.update("UPDATE products SET price = 99999"); // later price changes must not touch the snapshot

        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders", Integer.class)).isEqualTo(2 * 1500 + 3 * 700);
        assertThat(jdbc.queryForObject("SELECT price FROM order_items WHERE product_id = 'pA'", Integer.class)).isEqualTo(1500);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE product_id = 'pA'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT price FROM order_items WHERE product_id = 'pB'", Integer.class)).isEqualTo(700);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items WHERE product_id = 'pB'", Integer.class)).isEqualTo(3);
        assertThat(stock("pA")).isEqualTo(8);
        assertThat(stock("pB")).isEqualTo(7);
    }

    @Test
    void 실패_같은_상품_수량의_합이_int_범위를_넘으면_검증_오류이고_재고와_주문은_그대로다() {
        product("p1", 5);

        assertThatThrownBy(() -> order(line("p1", Integer.MAX_VALUE), line("p1", Integer.MAX_VALUE)))
                .isInstanceOf(ValidationException.class);

        assertThat(stock("p1")).isEqualTo(5);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
    }

    @Test
    void 성공_수량이_매우_커도_유효하면_주문된다() {
        product("p1", 2_000_000_000, 1);
        product("p2", 2_000_000_000, 1);

        order(line("p1", 500_000_000), line("p1", 500_000_000), line("p2", 1)); // p1 aggregates to 1e9

        assertThat(stock("p1")).isEqualTo(1_000_000_000);
        assertThat(stock("p2")).isEqualTo(1_999_999_999);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders", Integer.class)).isEqualTo(1_000_000_001);
    }

    @Test
    void 실패_주문_금액이_int_범위를_넘으면_검증_오류이고_재고와_주문은_그대로다() {
        product("p1", 100, 100_000_000);

        assertThatThrownBy(() -> order(line("p1", 100))).isInstanceOf(ValidationException.class); // 1e10

        assertThat(stock("p1")).isEqualTo(100);
        assertThat(count("orders")).isZero();
    }

    @Test
    void 실패_행_잠금이_풀리지_않으면_3초_뒤_잠금_오류로_끝나고_주문도_재고도_그대로다() throws Exception {
        product("p1", 5);

        Throwable[] failure = new Throwable[1];
        long elapsedMillis = holdingRowLock("p1", () -> {
            long start = System.nanoTime();
            failure[0] = failureOf(() -> order(line("p1", 1)), 15);
            return (System.nanoTime() - start) / 1_000_000;
        });

        assertThat(failure[0]).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(elapsedMillis).as("waited about lock_timeout (3 s)").isBetween(2_000L, 9_000L);
        assertThat(stock("p1")).isEqualTo(5);
        assertThat(count("orders")).isZero();
    }

    @Test
    void 성공_잠금이_풀려_있으면_같은_주문이_바로_성공한다() {
        product("p1", 5);

        order(line("p1", 1));

        assertThat(stock("p1")).isEqualTo(4);
    }

    @Test
    void 실패_품절_상품은_다른_주문이_행을_잠그고_있어도_기다리지_않고_바로_재고_부족이다() throws Exception {
        product("p1", 0);

        Throwable[] failure = new Throwable[1];
        long elapsedMillis = holdingRowLock("p1", () -> {
            long start = System.nanoTime();
            failure[0] = failureOf(() -> order(line("p1", 1)), 15);
            return (System.nanoTime() - start) / 1_000_000;
        });

        assertThat(failure[0]).isInstanceOf(InsufficientStockException.class);
        assertThat(elapsedMillis).as("rejected before any UPDATE, so no lock wait").isLessThan(2_000L);
        assertThat(count("orders")).isZero();
    }

    @Test
    void 실패_없는_상품이_섞이면_품절_상품이_있어도_상품_없음이_우선한다() {
        product("p1", 0);

        assertThatThrownBy(() -> order(line("p1", 1), line("missing", 1))).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void 실패_상품_id가_비어_있으면_검증_오류다() {
        assertThatThrownBy(() -> order(line(null, 1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> order(line("  ", 1))).isInstanceOf(ValidationException.class);
    }

    private interface Body {
        void run() throws Exception;
    }

    private interface TimedBody {
        long run() throws Exception;
    }

    /** Holds a FOR UPDATE row lock in a separate, open transaction while {@code body} runs; always rolled back after. */
    private long holdingRowLock(String productId, TimedBody body) throws Exception {
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT 1 FROM products WHERE id = '" + productId + "' FOR UPDATE");
                }
                return body.run();
            } finally {
                holder.rollback();
            }
        }
    }

    /** Runs the task on another thread; returns what it threw (null when it succeeded). Waits at most {@code seconds}. */
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

    /**
     * Installs a test-only BEFORE UPDATE trigger on products for the duration of {@code body}. Cleanup runs on its own
     * connection with a short lock_timeout, so a worker still holding a lock cannot hang it, and a failing cleanup is
     * attached to the original error instead of replacing it.
     */
    private void withProductsUpdateTrigger(String functionBody, Body body) throws Exception {
        Throwable failure = null;
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION test_products_update() RETURNS trigger AS $$ " + functionBody + " $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER test_products_update BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION test_products_update()");
            body.run();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                try {
                    st.execute("SET lock_timeout = '5s'");
                    st.execute("DROP TRIGGER IF EXISTS test_products_update ON products");
                    st.execute("DROP FUNCTION IF EXISTS test_products_update()");
                } finally {
                    st.execute("RESET lock_timeout"); // the connection goes back to the pool
                }
            } catch (Exception cleanupFailure) {
                if (failure == null) {
                    throw cleanupFailure;
                }
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Test
    void 실패_재고보다_많이_주문하면_재고는_그대로다() {
        product("p1", 3);

        assertThatThrownBy(() -> order(line("p1", 4))).isInstanceOf(InsufficientStockException.class);

        assertThat(stock("p1")).isEqualTo(3);
        assertThat(count("orders")).isZero();
    }

    @Test
    void 성공_두_상품을_서로_반대_순서로_동시에_주문해도_교착_상태_없이_모두_성공한다() throws Exception {
        product("pA", 1_000_000);
        product("pB", 1_000_000);
        int rounds = 100;
        int perRound = 4;

        for (int round = 0; round < rounds; round++) {
            List<Runnable> tasks = new ArrayList<>();
            for (int i = 0; i < perRound / 2; i++) {
                tasks.add(() -> order(line("pA", 1), line("pB", 1)));
                tasks.add(() -> order(line("pB", 1), line("pA", 1)));
            }
            assertThat(failures(runTogether(tasks))).as("round %d: failed orders", round).isZero();
        }

        assertThat(stock("pA")).isEqualTo(1_000_000 - rounds * perRound);
        assertThat(stock("pB")).isEqualTo(1_000_000 - rounds * perRound);
        assertThat(count("orders")).isEqualTo(rounds * perRound);
    }

    /**
     * Same scenario, but a test-only trigger holds each product row lock for 50 ms right after the row is locked, so
     * "A then B" and "B then A" updates overlap every time instead of only by luck.
     */
    @Test
    void 성공_행_잠금을_오래_잡아도_반대_순서_주문이_교착_상태_없이_모두_성공한다() throws Exception {
        product("pA", 1000);
        product("pB", 1000);
        withProductsUpdateTrigger("BEGIN PERFORM pg_sleep(0.05); RETURN NEW; END;", () -> {
            for (int round = 0; round < 5; round++) {
                List<Runnable> tasks = List.of(
                        () -> order(line("pA", 1), line("pB", 1)),
                        () -> order(line("pB", 1), line("pA", 1)));
                assertThat(failures(runTogether(tasks))).as("round %d: failed orders", round).isZero();
            }
        });

        assertThat(stock("pA")).isEqualTo(990);
        assertThat(stock("pB")).isEqualTo(990);
        assertThat(count("orders")).isEqualTo(10);
    }
}
