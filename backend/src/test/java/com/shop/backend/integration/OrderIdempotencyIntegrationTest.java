package com.shop.backend.integration;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * The per-user+key advisory lock on a real Postgres: a stuck holder must surface as the Spring lock exception (which
 * GlobalExceptionHandler maps to 503), not as a raw jakarta.persistence one (which would be a 500).
 */
class OrderIdempotencyIntegrationTest extends PostgresIntegrationTest {

    private static final String KEY = "key-12345";

    @Autowired
    private OrderService orderService;

    private void order(String userId, String key) {
        orderService.createOrder(userId, key, "구매자", "010-0000-0000", "서울", List.of(new OrderLine("p1", 1)));
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = 'p1'", Integer.class);
    }

    private int orders() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    private void product(int stock) {
        data.product("p1", null, 1000, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = 'p1'", stock);
    }

    @Test
    void 실패_같은_키의_잠금이_풀리지_않으면_3초_뒤_잠금_오류이고_풀린_뒤_재시도하면_한_번만_주문된다() throws Exception {
        String userId = data.user();
        product(5);

        Throwable failure;
        long elapsedMillis;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT pg_advisory_xact_lock(hashtextextended('" + userId + ":" + KEY + "', 0))");
                }
                long start = System.nanoTime();
                failure = failureOf(() -> order(userId, KEY), 15);
                elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            } finally {
                holder.rollback();
            }
        }

        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(elapsedMillis).as("waited about lock_timeout (3 s)").isBetween(2_000L, 9_000L);
        assertThat(orders()).isZero();
        assertThat(stock()).isEqualTo(5);

        order(userId, KEY); // lock released: the retry goes through
        order(userId, KEY); // and a second retry replays instead of ordering again

        assertThat(orders()).isEqualTo(1);
        assertThat(stock()).isEqualTo(4);
    }

    @Test
    void 성공_다른_사용자의_같은_키는_서로_기다리지_않고_각각_주문된다() throws Exception {
        String holderUser = data.user();
        String otherUser = data.user();
        product(5);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT pg_advisory_xact_lock(hashtextextended('" + holderUser + ":" + KEY + "', 0))");
                }
                order(otherUser, KEY);
            } finally {
                holder.rollback();
            }
        }

        assertThat(orders()).isEqualTo(1);
    }

    @Test
    void 성공_주문자_본인만_주문을_조회한다() {
        String owner = data.user();
        String other = data.user();
        product(5);
        order(owner, KEY);
        String orderId = jdbc.queryForObject("SELECT id FROM orders", String.class);

        assertThat(orderService.getOrder(orderId, owner)).isPresent();
        assertThat(orderService.getOrder(orderId, other)).isEmpty();
        assertThat(orderService.getOrder("missing", owner)).isEmpty();
    }

    @Test
    void 실패_주문자가_없거나_비어_있으면_주문자_없는_옛_주문도_조회되지_않는다() {
        product(5);
        data.sale("p1", "PAID", 1, TestData.T0); // legacy order: user_id NULL
        String legacyId = jdbc.queryForObject("SELECT id FROM orders", String.class);

        assertThat(orderService.getOrder(legacyId, null)).isEmpty();
        assertThat(orderService.getOrder(legacyId, "")).isEmpty();
        assertThat(orderService.getOrder(legacyId, "  ")).isEmpty();
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

    // ---- 아래는 멱등 재생/충돌/동시성/소유권 시나리오 (위 4개 테스트와 별개) ----

    private void productWith(String id, int stock) {
        data.product(id, null, 1000, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    private int stockOf(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private Order placeOrder(String userId, String key, String address, OrderLine... lines) {
        return orderService.createOrder(userId, key, "구매자", "010-0000-0000", address, List.of(lines));
    }

    private Order placeOrder(String userId, String key, int quantity) {
        return placeOrder(userId, key, "서울", new OrderLine("p1", quantity));
    }

    @Test
    void 성공_같은_사용자_키_내용으로_두_번_주문하면_같은_주문이고_재고는_한_번만_줄어든다() {
        productWith("p1", 10);
        String user = data.user();

        Order first = placeOrder(user, KEY, 3);
        Order second = placeOrder(user, KEY, 3);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
        assertThat(stockOf("p1")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class)).isEqualTo(3);
    }

    @Test
    void 성공_공백과_같은_상품_줄_분할_순서만_다른_같은_내용은_재생으로_본다() {
        productWith("p1", 10);
        productWith("p2", 10);
        String user = data.user();

        Order first = orderService.createOrder(user, KEY, "구매자", "010-0000-0000", "서울",
                List.of(new OrderLine("p1", 2), new OrderLine("p2", 1)));
        Order second = orderService.createOrder(user, KEY, " 구매자 ", "010-0000-0000 ", " 서울",
                List.of(new OrderLine("p2", 1), new OrderLine("p1", 1), new OrderLine("p1", 1)));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf("p1")).isEqualTo(8);
        assertThat(stockOf("p2")).isEqualTo(9);
    }

    @Test
    void 성공_같은_사용자_키_내용으로_동시에_보내도_주문은_한_건이고_모두_같은_주문을_받는다() throws Exception {
        for (int round = 0; round < 10; round++) { // the race is timing dependent
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            productWith("p1", 100);
            String user = data.user(); // created before any thread starts, so only the order itself races
            int threads = 8;

            List<Callable<Order>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> placeOrder(user, KEY, 2));
            }
            List<Order> results = runTogether(tasks);

            assertThat(results).as("round %d", round).hasSize(threads);
            assertThat(results.stream().map(Order::getId).distinct().toList()).as("round %d: order ids", round).hasSize(1);
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(1);
            assertThat(count("order_items")).as("round %d: order items", round).isEqualTo(1);
            assertThat(stockOf("p1")).as("round %d: stock", round).isEqualTo(98);
        }
    }

    @Test
    void 성공_같은_사용자_키로_내용이_다른_요청이_동시에_와도_한_건만_만들어지고_나머지는_충돌_예외다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            productWith("p1", 100);
            String user = data.user();

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                int quantity = i; // 4 different bodies under one key
                tasks.add(() -> {
                    try {
                        return placeOrder(user, KEY, quantity);
                    } catch (IdempotencyKeyReuseException e) {
                        return e;
                    }
                });
            }
            List<Object> results = runTogether(tasks);

            assertThat(results.stream().filter(r -> r instanceof Order).count()).as("round %d: winners", round).isEqualTo(1);
            assertThat(results.stream().filter(r -> r instanceof IdempotencyKeyReuseException).count()).as("round %d: conflicts", round).isEqualTo(3);
            assertThat(count("orders")).as("round %d: orders", round).isEqualTo(1);
            int ordered = jdbc.queryForObject("SELECT quantity FROM order_items", Integer.class);
            assertThat(stockOf("p1")).as("round %d: stock", round).isEqualTo(100 - ordered);
        }
    }

    @Test
    void 실패_같은_사용자_키로_수량이_다르면_충돌_예외이고_주문과_재고는_그대로다() {
        productWith("p1", 10);
        String user = data.user();
        Order first = placeOrder(user, KEY, 3);

        assertThatThrownBy(() -> placeOrder(user, KEY, 4)).isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
        assertThat(stockOf("p1")).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT id FROM orders", String.class)).isEqualTo(first.getId());
    }

    @Test
    void 실패_같은_사용자_키로_주소가_다르면_충돌_예외이고_주문과_재고는_그대로다() {
        productWith("p1", 10);
        String user = data.user();
        placeOrder(user, KEY, "서울", new OrderLine("p1", 3));

        assertThatThrownBy(() -> placeOrder(user, KEY, "부산", new OrderLine("p1", 3)))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(count("orders")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT customer_address FROM orders", String.class)).isEqualTo("서울");
        assertThat(stockOf("p1")).isEqualTo(7);
    }

    @Test
    void 실패_같은_키로_다른_상품을_추가하면_충돌_예외이고_추가_상품_재고는_그대로다() {
        productWith("p1", 10);
        productWith("p2", 10);
        String user = data.user();
        placeOrder(user, KEY, "서울", new OrderLine("p1", 1));

        assertThatThrownBy(() -> placeOrder(user, KEY, "서울", new OrderLine("p1", 1), new OrderLine("p2", 1)))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(stockOf("p2")).isEqualTo(10);
        assertThat(count("order_items")).isEqualTo(1);
    }

    @Test
    void 성공_다른_사용자가_같은_키_문자열을_써도_각자_별개_주문이다() {
        productWith("p1", 10);
        String userA = data.user();
        String userB = data.user();

        Order a = placeOrder(userA, KEY, 2);
        Order b = placeOrder(userB, KEY, 3); // even a different body: the key is per user

        assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(count("orders")).isEqualTo(2);
        assertThat(stockOf("p1")).isEqualTo(5);
        assertThat(placeOrder(userA, KEY, 2).getId()).isEqualTo(a.getId());
        assertThat(placeOrder(userB, KEY, 3).getId()).isEqualTo(b.getId());
        assertThat(stockOf("p1")).isEqualTo(5);
    }

    @Test
    void 성공_한_사용자가_다른_키를_쓰면_별개_주문이다() {
        productWith("p1", 10);
        String user = data.user();

        Order a = placeOrder(user, "key-0000-000a", 1);
        Order b = placeOrder(user, "key-0000-000b", 1);

        assertThat(b.getId()).isNotEqualTo(a.getId());
        assertThat(stockOf("p1")).isEqualTo(8);
    }

    @Test
    void 성공_재고가_0이_된_뒤의_같은_키_재생도_성공하고_재고는_그대로다() {
        productWith("p1", 1);
        String user = data.user();
        Order first = placeOrder(user, KEY, 1);
        assertThat(stockOf("p1")).isZero();

        Order replay = placeOrder(user, KEY, 1); // would be InsufficientStockException as a new order

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(stockOf("p1")).isZero();
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void 실패_재고가_0이면_새_키의_같은_내용은_재고_부족이다() {
        productWith("p1", 1);
        String user = data.user();
        placeOrder(user, KEY, 1);

        assertThatThrownBy(() -> placeOrder(user, "key-0000-0002", 1)).isInstanceOf(InsufficientStockException.class);

        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void 성공_재고_부족으로_실패한_키는_주문이_남지_않고_재고를_채운_뒤_같은_키로_새로_성공한다() {
        productWith("p1", 1);
        String user = data.user();

        assertThatThrownBy(() -> placeOrder(user, KEY, 2)).isInstanceOf(InsufficientStockException.class);
        assertThat(count("orders")).isZero();
        assertThat(count("order_items")).isZero();
        assertThat(stockOf("p1")).isEqualTo(1);

        jdbc.update("UPDATE products SET stock = 5 WHERE id = 'p1'");
        Order retried = placeOrder(user, KEY, 2);

        assertThat(retried.getId()).isNotNull();
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf("p1")).isEqualTo(3);
        assertThat(placeOrder(user, KEY, 2).getId()).isEqualTo(retried.getId());
        assertThat(stockOf("p1")).isEqualTo(3);
    }

    @Test
    void 성공_본인_주문은_조회되고_상품명과_수량이_들어_있다() {
        productWith("p1", 10);
        String user = data.user();
        Order created = placeOrder(user, KEY, 3);

        Optional<Order> found = orderService.getOrder(created.getId(), user);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(user);
        assertThat(found.get().getItems()).hasSize(1);
        assertThat(found.get().getItems().get(0).getProduct().getName()).isEqualTo("상품 p1");
        assertThat(found.get().getItems().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    void 실패_다른_사용자의_주문과_없는_주문_id는_조회되지_않는다() {
        productWith("p1", 10);
        String owner = data.user();
        Order created = placeOrder(owner, KEY, 1);

        assertThat(orderService.getOrder(created.getId(), data.user())).isEmpty();
        assertThat(orderService.getOrder("no-such-order", owner)).isEmpty();
        assertThat(orderService.getOrder(created.getId(), owner)).isPresent();
    }

    @Test
    void 실패_주문자가_없는_옛_주문은_실제_사용자에게도_조회되지_않는다() {
        productWith("p1", 10);
        data.sale("p1", "PAID", 1, TestData.T0); // legacy shape: user_id NULL
        String legacyId = jdbc.queryForObject("SELECT id FROM orders", String.class);

        assertThat(orderService.getOrder(legacyId, data.user())).isEmpty();
    }

    /** All tasks start at the same instant (barrier); any exception thrown by a task fails the test. */
    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
