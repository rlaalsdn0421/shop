package com.shop.backend.integration;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.error.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
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
        data.product(id, null, 1000, null, TestData.T0);
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

        // both request orders: whichever product is updated first, the other's failure must undo it
        assertThatThrownBy(() -> order(line("pA", 2), line("pB", 2))).isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> order(line("pB", 2), line("pA", 2))).isInstanceOf(InsufficientStockException.class);

        assertThat(stock("pA")).isEqualTo(5);
        assertThat(stock("pB")).isEqualTo(1);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
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
        jdbc.execute("CREATE FUNCTION slow_products_update() RETURNS trigger AS $$ BEGIN PERFORM pg_sleep(0.05); RETURN NEW; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER slow_products_update BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION slow_products_update()");
        try {
            for (int round = 0; round < 5; round++) {
                List<Runnable> tasks = List.of(
                        () -> order(line("pA", 1), line("pB", 1)),
                        () -> order(line("pB", 1), line("pA", 1)));
                assertThat(failures(runTogether(tasks))).as("round %d: failed orders", round).isZero();
            }
        } finally {
            jdbc.execute("DROP TRIGGER slow_products_update ON products");
            jdbc.execute("DROP FUNCTION slow_products_update()");
        }

        assertThat(stock("pA")).isEqualTo(990);
        assertThat(stock("pB")).isEqualTo(990);
        assertThat(count("orders")).isEqualTo(10);
    }
}
