package com.shop.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.application.service.PaymentGateway.Payment;
import com.shop.backend.application.service.PaymentService;
import com.shop.backend.application.service.PaymentTransactions;
import com.shop.backend.application.service.WebhookSignatureVerifier;
import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.InsufficientStockException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.presentation.dto.OrderDtos;
import com.shop.backend.presentation.dto.ProductDtos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-option stock and price (V18) on a real Postgres: the schema constraints, product registration with options, and
 * the whole money path (order, idempotency, oversell races, lock order, expiry, late payment, cancel/refund, reads).
 * Every money assertion looks at ALL stock rows (products and product_options) before and after, so "the wrong table
 * got the stock" cannot slip through. Only the payment provider is faked ({@link StubGateway}).
 *
 * <p>Catalog used by most tests (see {@link #catalog()}): pA legacy (stock 10, 1,000), pB with options pB-1 (white/M,
 * 20,000, stock 10) and pB-2 (white/L, 25,000, stock 5) whose products.stock is 0 like a real option product, pC legacy
 * (stock 10, 3,000). The StockKey order is pA, pB/pB-1, pB/pB-2, pC.
 */
@TestPropertySource(properties = {
        "portone.store-id=" + ProductOptionsIntegrationTest.STORE_ID,
        "portone.webhook-secret=" + ProductOptionsIntegrationTest.WEBHOOK_SECRET,
        "portone.allow-test-channel=false"})
class ProductOptionsIntegrationTest extends PostgresIntegrationTest {

    static final String STORE_ID = "store-test-0001";
    // Made-up key, base64 of "integration-test-webhook-key-0123" (not a real secret)
    static final String WEBHOOK_SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC13ZWJob29rLWtleS0wMTIz";
    private static final byte[] WEBHOOK_KEY = "integration-test-webhook-key-0123".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = IntegrationTestConfig.FIXED_NOW;

    private static final String NAME = "구매자";
    private static final String PHONE = "010-0000-0000";
    private static final String ADDR = "서울";

    // Stock row keys of snapshot(): "p:" = products.stock, "o:" = product_options.stock
    private static final String P_A = "p:pA";
    private static final String P_B = "p:pB";
    private static final String O_B1 = "o:pB-1";
    private static final String O_B2 = "o:pB-2";
    private static final String P_C = "p:pC";

    // The request hashes of option-less orders as computed before V18 (same constants as OrderRequestHashTest).
    private static final String LEGACY_ONE_LINE = "187e6e5779fd48666a66f9a860fdec9a3d18b36c8b8ac98b9de26e274ea9614d";
    private static final String LEGACY_TWO_LINES = "58c133d56028c42480bed1a827a738f55806dbd8179d9e7b905f2b15d206a87d";

    /** Fake provider: payments are controlled per payment id; cancel() marks the payment CANCELLED unless told to refuse. */
    public static class StubGateway implements PaymentGateway {
        final Map<String, Payment> payments = new ConcurrentHashMap<>();
        final List<String> cancelCalls = new CopyOnWriteArrayList<>();
        final AtomicInteger findCalls = new AtomicInteger();
        volatile boolean unavailable;
        volatile boolean rejectCancel;

        void reset() {
            payments.clear();
            cancelCalls.clear();
            findCalls.set(0);
            unavailable = false;
            rejectCancel = false;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<Payment> find(String paymentId) {
            findCalls.incrementAndGet();
            if (unavailable) {
                throw new PaymentProviderUnavailableException();
            }
            return Optional.ofNullable(payments.get(paymentId));
        }

        @Override
        public void cancel(String paymentId, String reason, Requester requester) {
            cancelCalls.add(paymentId);
            if (rejectCancel) {
                throw new CancelProviderUnavailableException(false);
            }
            payments.computeIfPresent(paymentId, (id, p) ->
                    new Payment(p.id(), p.storeId(), "CANCELLED", p.total(), p.currency(), p.transactionId(), p.channelType()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PaymentService.class, PaymentTransactions.class, WebhookSignatureVerifier.class, OrderCancelService.class})
    static class OptionsTestConfig {
        @Bean
        StubGateway stubGateway() {
            return new StubGateway();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderCancelService cancelService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentTransactions transactions;
    @Autowired
    private StubGateway gateway;

    @org.junit.jupiter.api.BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    // ================================================================== helpers: data

    private void legacy(String id, int stock, int price) {
        data.product(id, null, price, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    /** A product that sells by option: products.stock is 0 (unused), products.price the cheapest option's, like createProduct does. */
    private void optionProduct(String id, int cheapestPrice) {
        legacy(id, 0, cheapestPrice);
    }

    private void insertOption(String id, String productId, String color, String hex, String size, int price,
                              Integer originalPrice, int stock, int sortOrder) {
        jdbc.update("INSERT INTO product_options (id, product_id, color_name, color_hex, size_label, price, original_price, "
                        + "stock, sort_order, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
                id, productId, color, hex, size, price, originalPrice, stock, sortOrder);
    }

    private void option(String id, String productId, String color, String size, int price, int stock, int sortOrder) {
        insertOption(id, productId, color, null, size, price, null, stock, sortOrder);
    }

    private void catalog() {
        legacy("pA", 10, 1000);
        optionProduct("pB", 20000);
        option("pB-1", "pB", "화이트", "M", 20000, 10, 0);
        option("pB-2", "pB", "화이트", "L", 25000, 5, 1);
        legacy("pC", 10, 3000);
    }

    private void resetWithCatalog() {
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        gateway.reset();
        catalog();
    }

    private static OrderLine line(String productId, int quantity) {
        return new OrderLine(productId, quantity);
    }

    private static OrderLine opt(String productId, String optionId, int quantity) {
        return new OrderLine(productId, quantity, optionId);
    }

    /** pA x2 (legacy), pB-1 x1, pB-2 x2 (options), pC x1 (legacy): 2,000 + 20,000 + 50,000 + 3,000 = 75,000 (free shipping). */
    private static OrderLine[] fullLines() {
        return new OrderLine[]{line("pA", 2), opt("pB", "pB-1", 1), opt("pB", "pB-2", 2), line("pC", 1)};
    }

    private static final int FULL_TOTAL = 75_000;

    /** Stocks of {@link #baseline()} after one {@link #fullLines()} order has reserved its units. */
    private static Map<String, Integer> afterFull(Map<String, Integer> base) {
        return but(base, P_A, 8, O_B1, 9, O_B2, 3, P_C, 9);
    }

    // ================================================================== helpers: state

    /** Every stock row: products ("p:id") and options ("o:id"). */
    private Map<String, Integer> stocks() {
        Map<String, Integer> result = new TreeMap<>();
        jdbc.query("SELECT 'p:' || id, stock FROM products UNION ALL SELECT 'o:' || id, stock FROM product_options",
                (RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getInt(2)));
        return result;
    }

    private Map<String, Integer> baseline() {
        return stocks();
    }

    private static Map<String, Integer> but(Map<String, Integer> base, Object... keyValues) {
        Map<String, Integer> copy = new TreeMap<>(base);
        for (int i = 0; i < keyValues.length; i += 2) {
            assertThat(copy).containsKey((String) keyValues[i]);
            copy.put((String) keyValues[i], (Integer) keyValues[i + 1]);
        }
        return copy;
    }

    private void setStock(String key, int stock) {
        String id = key.substring(2);
        jdbc.update(key.startsWith("p:") ? "UPDATE products SET stock = ? WHERE id = ?"
                : "UPDATE product_options SET stock = ? WHERE id = ?", stock, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    /** All stocks plus the row counts that a refused request must leave alone. */
    private record Snap(Map<String, Integer> stocks, int orders, int items) {
    }

    private Snap snap() {
        return new Snap(stocks(), count("orders"), count("order_items"));
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private Map<String, Object> orderRow(String orderId) {
        return jdbc.queryForMap("SELECT status, expires_at, paid_at, payment_transaction_id, shipped_at, delivered_at, "
                + "cancelled_at, cancel_from, cancel_reason FROM orders WHERE id = ?", orderId);
    }

    /** One text per line: product|option|label|quantity|price, '-' for NULL. */
    private List<String> itemRows(String orderId) {
        return jdbc.queryForList("SELECT product_id || '|' || COALESCE(option_id, '-') || '|' || COALESCE(option_label, '-') || '|' "
                + "|| quantity || '|' || price FROM order_items WHERE order_id = ?", String.class, orderId);
    }

    // ================================================================== helpers: orders and payments

    private record Placed(String userId, String orderId, int total, String key, OrderLine[] lines) {
    }

    private Placed place(String userId, OrderLine... lines) {
        return placeWithKey(userId, "key-" + UUID.randomUUID(), lines);
    }

    private Placed placeWithKey(String userId, String key, OrderLine... lines) {
        Order order = orderService.createOrder(userId, key, NAME, PHONE, ADDR, List.of(lines));
        return new Placed(userId, order.getId(), order.getTotalAmount(), key, lines);
    }

    private Order replay(Placed p) {
        return orderService.createOrder(p.userId(), p.key(), NAME, PHONE, ADDR, List.of(p.lines()));
    }

    private void order(String userId, OrderLine... lines) {
        orderService.createOrder(userId, "key-" + UUID.randomUUID(), NAME, PHONE, ADDR, List.of(lines));
    }

    private void overdue(String orderId) {
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), orderId);
    }

    private void providerPaid(Placed p) {
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW",
                "tx-" + p.orderId().substring(0, 8), "LIVE"));
    }

    private Placed paid(String userId, OrderLine... lines) {
        Placed p = place(userId, lines);
        providerPaid(p);
        assertThat(paymentService.complete(p.orderId(), userId)).isEqualTo("PAID");
        return p;
    }

    /** Placed, then swept by the real expiry job (stock given back). */
    private Placed expired(String userId, OrderLine... lines) {
        Placed p = place(userId, lines);
        overdue(p.orderId());
        assertThat(paymentService.expirePending()).isEqualTo(1);
        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        return p;
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private void webhook(String type, String paymentId) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"" + STORE_ID + "\",\"transactionId\":\"tx-1\"}}";
        String id = "msg_" + UUID.randomUUID();
        String ts = String.valueOf(NOW.getEpochSecond());
        paymentService.handleWebhook(id, ts, sign(id, ts, body), body);
    }

    // ================================================================== helpers: concurrency

    /** Runs all tasks at the same instant; an exception is returned as the task's result instead of being thrown. */
    private List<Object> runTogether(List<Callable<Object>> tasks) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Throwable> throwables(List<Object> results) {
        return results.stream().filter(r -> r instanceof Throwable).map(r -> (Throwable) r).toList();
    }

    private static void assertNoThrowable(List<Object> results, String context) {
        assertThat(throwables(results)).as(context).isEmpty();
    }

    /** Exactly {@code expected} tasks failed and every failure is a plain "sold out"; a deadlock or lock timeout would show up here. */
    private static List<Throwable> assertOnlyInsufficientStock(List<Object> results, int expected, String context) {
        List<Throwable> failures = throwables(results);
        assertThat(failures).as(context).hasSize(expected)
                .allSatisfy(t -> assertThat(t).isInstanceOf(InsufficientStockException.class));
        return failures;
    }

    private List<Object> raceOrders(List<OrderLine[]> lineSets) throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (OrderLine[] lines : lineSets) {
            String user = data.user();
            tasks.add(() -> place(user, lines).orderId());
        }
        return runTogether(tasks);
    }

    private interface Body {
        void run() throws Exception;
    }

    /**
     * Installs a test-only BEFORE UPDATE trigger on {@code table} for the duration of {@code body}. Cleanup runs on its
     * own connection with a short lock_timeout; a failing cleanup is attached to the original error.
     */
    private void withUpdateTrigger(String table, String functionBody, Body body) throws Exception {
        String fn = "test_upd_" + table;
        Throwable failure = null;
        try {
            jdbc.execute("CREATE OR REPLACE FUNCTION " + fn + "() RETURNS trigger AS $$ " + functionBody + " $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER " + fn + " BEFORE UPDATE ON " + table + " FOR EACH ROW EXECUTE FUNCTION " + fn + "()");
            body.run();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
                try {
                    st.execute("SET lock_timeout = '5s'");
                    st.execute("DROP TRIGGER IF EXISTS " + fn + " ON " + table);
                    st.execute("DROP FUNCTION IF EXISTS " + fn + "()");
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

    /** Every stock UPDATE (products and options) keeps its row lock for 30 ms, so opposite lock orders overlap every time. */
    private void withSlowStockUpdates(Body body) throws Exception {
        String sleep = "BEGIN PERFORM pg_sleep(0.03); RETURN NEW; END;";
        withUpdateTrigger("products", sleep, () -> withUpdateTrigger("product_options", sleep, body));
    }

    /** Holds the order row FOR UPDATE on its own connection (open transaction) until commit()/rollback(). */
    private final class RowHolder implements AutoCloseable {
        private final Connection connection;

        RowHolder(String orderId) throws Exception {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.execute("SELECT 1 FROM orders WHERE id = '" + orderId + "' FOR UPDATE");
            }
        }

        void markPaidAndCommit(String orderId) throws Exception {
            try (Statement st = connection.createStatement()) {
                st.execute("UPDATE orders SET status = 'PAID', paid_at = now(), payment_transaction_id = 'tx-holder' WHERE id = '" + orderId + "'");
            }
            connection.commit();
        }

        @Override
        public void close() throws Exception {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** Waits until some other backend is blocked on a lock, so the test knows the worker really queued behind the holder. */
    private void awaitBlockedBackend() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the worker never blocked on the order row lock");
    }

    // ================================================================== 1. V18 schema and constraints

    private void insertProductRow(String id) {
        jdbc.update("INSERT INTO products (id, name, description, price, image_url, stock, created_at) "
                + "VALUES (?, 'n', 'd', 1000, 'http://img/x', 1, now())", id);
    }

    private void insertOrderRow(String id) {
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                + "VALUES (?, 'n', 'p', 'a', 1, 'PAID', now())", id);
    }

    private void rejected(Runnable insert, String messagePart) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(messagePart);
    }

    @Test
    void 성공_product_options의_컬럼_타입_길이_널허용이_V18대로_있다() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name || ':' || data_type || ':' || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable "
                        + "FROM information_schema.columns WHERE table_name = 'product_options' AND table_schema = current_schema()",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id:character varying:36:NO", "product_id:character varying:36:NO",
                "color_name:character varying:30:YES", "color_hex:character varying:7:YES",
                "size_label:character varying:30:YES", "price:integer:-:NO", "original_price:integer:-:YES",
                "stock:integer:-:NO", "sort_order:integer:-:NO", "created_at:timestamp with time zone:-:NO");
        assertThat(jdbc.queryForList("SELECT column_name || ':' || data_type || ':' || COALESCE(character_maximum_length::text, '-') "
                        + "|| ':' || is_nullable FROM information_schema.columns WHERE table_name = 'order_items' "
                        + "AND column_name IN ('option_id', 'option_label') AND table_schema = current_schema()", String.class))
                .containsExactlyInAnyOrder("option_id:character varying:36:YES", "option_label:character varying:80:YES");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_product_options_product' "
                + "AND schemaname = current_schema()", String.class)).contains("(product_id, sort_order)");
    }

    @Test
    void 성공_price는_0부터_허용하고_음수는_DB가_거부한다() {
        insertProductRow("p1");

        assertThatCode(() -> insertOption("o0", "p1", "a", null, null, 0, null, 1, 0)).doesNotThrowAnyException();
        rejected(() -> insertOption("o1", "p1", "b", null, null, -1, null, 1, 1), "violates check constraint");
        assertThat(count("product_options")).isEqualTo(1);
    }

    @Test
    void 실패_original_price는_price보다_커야_하고_같거나_작으면_거부한다() {
        insertProductRow("p1");

        assertThatCode(() -> insertOption("ok1", "p1", "a", null, null, 1000, 1001, 1, 0)).doesNotThrowAnyException();
        assertThatCode(() -> insertOption("ok2", "p1", "b", null, null, 1000, null, 1, 1)).doesNotThrowAnyException();
        rejected(() -> insertOption("eq", "p1", "c", null, null, 1000, 1000, 1, 2), "violates check constraint");
        rejected(() -> insertOption("lt", "p1", "d", null, null, 1000, 999, 1, 3), "violates check constraint");
        rejected(() -> insertOption("zero", "p1", "e", null, null, 0, 0, 1, 4), "violates check constraint");
        assertThat(count("product_options")).isEqualTo(2);
    }

    @Test
    void 성공_stock은_0부터_허용하고_음수는_DB가_거부한다() {
        insertProductRow("p1");

        assertThatCode(() -> insertOption("o0", "p1", "a", null, null, 1000, null, 0, 0)).doesNotThrowAnyException();
        rejected(() -> insertOption("o1", "p1", "b", null, null, 1000, null, -1, 1), "violates check constraint");
        assertThat(count("product_options")).isEqualTo(1);
    }

    @Test
    void 실패_색상도_사이즈도_없는_옵션은_거부하고_둘_중_하나만_있으면_허용한다() {
        insertProductRow("p1");

        rejected(() -> insertOption("none", "p1", null, null, null, 1000, null, 1, 0), "violates check constraint");
        assertThatCode(() -> insertOption("color", "p1", "화이트", null, null, 1000, null, 1, 1)).doesNotThrowAnyException();
        insertProductRow("p2");
        assertThatCode(() -> insertOption("size", "p2", null, null, "M", 1000, null, 1, 0)).doesNotThrowAnyException();
        assertThat(count("product_options")).isEqualTo(2);
    }

    @Test
    void 성공_color_hex는_대문자_소문자_섞인_6자리_16진수를_허용한다() {
        insertProductRow("p1");

        assertThatCode(() -> {
            insertOption("upper", "p1", "a", "#ABCDEF", null, 1000, null, 1, 0);
            insertOption("lower", "p1", "b", "#abcdef", null, 1000, null, 1, 1);
            insertOption("mixed", "p1", "c", "#a1B2c3", null, 1000, null, 1, 2);
            insertOption("digits", "p1", "d", "#000000", null, 1000, null, 1, 3);
        }).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT color_hex FROM product_options WHERE id = 'lower'", String.class)).isEqualTo("#abcdef");
        assertThat(count("product_options")).isEqualTo(4);
    }

    @Test
    void 실패_color_hex가_형식에_맞지_않으면_거부한다() {
        insertProductRow("p1");

        for (String bad : new String[]{"red", "#12345", "#GGGGGG", "123456", "#12 456", "#12345G", "#abcdeg"}) {
            rejected(() -> insertOption(UUID.randomUUID().toString(), "p1", "a" + bad.length(), bad, null, 1000, null, 1, 0),
                    "violates check constraint");
        }
        // 8 characters do not fit VARCHAR(7); either way the row must not exist
        assertThatThrownBy(() -> insertOption("long", "p1", "z", "#1234567", null, 1000, null, 1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("product_options")).isZero();
    }

    @Test
    void 실패_color_hex는_색상_이름이_있어야_하고_사이즈만_있는_옵션에는_넣을_수_없다() {
        insertProductRow("p1");

        rejected(() -> insertOption("o1", "p1", null, "#ffffff", "M", 1000, null, 1, 0), "violates check constraint");
        assertThatCode(() -> insertOption("o2", "p1", "화이트", "#ffffff", "M", 1000, null, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> insertOption("o3", "p1", "블랙", null, "M", 1000, null, 1, 2)).doesNotThrowAnyException();
        assertThat(count("product_options")).isEqualTo(2);
    }

    @Test
    void 실패_같은_상품의_같은_색상과_사이즈_조합은_거부하고_다른_상품이면_허용한다() {
        insertProductRow("p1");
        insertProductRow("p2");
        insertOption("o1", "p1", "화이트", null, "M", 1000, null, 1, 0);

        rejected(() -> insertOption("dup", "p1", "화이트", null, "M", 2000, null, 5, 1), "uq_product_options_combo");
        assertThatCode(() -> insertOption("other-size", "p1", "화이트", null, "L", 1000, null, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> insertOption("other-color", "p1", "블랙", null, "M", 1000, null, 1, 2)).doesNotThrowAnyException();
        assertThatCode(() -> insertOption("other-product", "p2", "화이트", null, "M", 1000, null, 1, 0)).doesNotThrowAnyException();
        assertThat(count("product_options")).isEqualTo(4);
    }

    @Test
    void 실패_색상_없이_사이즈만_같은_두_옵션도_NULL을_빈_문자열로_보고_중복으로_거부한다() {
        insertProductRow("p1");
        insertProductRow("p2");
        insertOption("s1", "p1", null, null, "M", 1000, null, 1, 0);
        rejected(() -> insertOption("s2", "p1", null, null, "M", 1000, null, 1, 1), "uq_product_options_combo");

        insertOption("c1", "p2", "블랙", null, null, 1000, null, 1, 0);
        rejected(() -> insertOption("c2", "p2", "블랙", null, null, 1000, null, 1, 1), "uq_product_options_combo");

        // NULL color + size 'a' is not the same as color 'a' + NULL size
        assertThatCode(() -> {
            insertOption("size-a", "p1", null, null, "a", 1000, null, 1, 2);
            insertOption("color-a", "p1", "a", null, null, 1000, null, 1, 3);
        }).doesNotThrowAnyException();
        assertThat(count("product_options")).isEqualTo(4);
    }

    @Test
    void 성공_대소문자만_다른_조합은_DB가_아니라_서비스_검증이_막는다() {
        insertProductRow("p1");
        insertOption("o1", "p1", "Red", null, "M", 1000, null, 1, 0);

        assertThatCode(() -> insertOption("o2", "p1", "red", null, "m", 1000, null, 1, 1)).doesNotThrowAnyException();
    }

    @Test
    void 실패_없는_상품의_옵션은_외래키가_거부한다() {
        rejected(() -> insertOption("o1", "no-such-product", "a", null, null, 1000, null, 1, 0), "violates foreign key constraint");
        assertThat(count("product_options")).isZero();
    }

    @Test
    void 실패_order_items의_option_id는_없는_옵션을_가리킬_수_없다() {
        insertProductRow("p1");
        insertOrderRow("ord1");
        insertOption("o1", "p1", "a", null, null, 1000, null, 1, 0);

        assertThatCode(() -> jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price, option_id, option_label) "
                + "VALUES ('i1', 'ord1', 'p1', 1, 1000, 'o1', 'a')")).doesNotThrowAnyException();
        rejected(() -> jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price, option_id, option_label) "
                + "VALUES ('i2', 'ord1', 'p1', 1, 1000, 'ghost', 'a')"), "violates foreign key constraint");
        assertThat(count("order_items")).isEqualTo(1);
    }

    @Test
    void 성공_옵션_이름_스냅샷은_80자까지_저장되고_81자는_거부한다() {
        insertProductRow("p1");
        insertOrderRow("ord1");

        assertThatCode(() -> jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price, option_label) "
                + "VALUES ('i1', 'ord1', 'p1', 1, 1000, ?)", "x".repeat(80))).doesNotThrowAnyException();
        rejected(() -> jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price, option_label) "
                + "VALUES ('i2', 'ord1', 'p1', 1, 1000, ?)", "x".repeat(81)), "value too long");
        assertThat(count("order_items")).isEqualTo(1);
    }

    @Test
    void 성공_옵션_없는_옛_주문_줄은_option_id와_option_label이_NULL인_채로_유효하다() {
        insertProductRow("p1");
        insertOrderRow("ord1");

        jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price) VALUES ('old', 'ord1', 'p1', 2, 1000)");

        assertThat(jdbc.queryForMap("SELECT option_id, option_label FROM order_items WHERE id = 'old'"))
                .containsEntry("option_id", null).containsEntry("option_label", null);
    }

    @Test
    void 성공_products_price_varies는_NOT_NULL이고_기본값_FALSE이다() {
        insertProductRow("p1");

        assertThat(jdbc.queryForObject("SELECT price_varies FROM products WHERE id = 'p1'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'products' "
                + "AND column_name = 'price_varies' AND table_schema = current_schema()", String.class)).isEqualTo("NO");
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET price_varies = NULL WHERE id = 'p1'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ================================================================== 2. createProduct with options (real service)

    private static NewProductOption no(String color, String hex, String size, Integer price, Integer original, Integer stock) {
        return new NewProductOption(color, hex, size, price, original, stock);
    }

    private static final List<String> TWO_IMAGES = List.of("https://img.example.com/1.png", "https://img.example.com/2.png");

    private Product create(List<NewProductOption> options) {
        // top-level price / stock / originalPrice must be ignored when options are present
        return productService.createProduct("옵션 상품", "설명", 99_999, "https://legacy.example.com/a.png", TWO_IMAGES, 77, null,
                123_456, null, options);
    }

    private Map<String, Object> productRow(String id) {
        return jdbc.queryForMap("SELECT price, original_price, price_varies, stock, image_url FROM products WHERE id = ?", id);
    }

    @Test
    void 성공_옵션_상품을_만들면_행_순서_최저가_정가_가격차이_재고0_이미지가_저장된다() {
        Product saved = create(List.of(
                no("화이트", "#FFFFFF", "M", 20_000, 25_000, 4),
                no("화이트", "#ffffff", "L", 19_000, null, 6),
                no("블랙", null, "M", 21_000, 30_000, 0)));

        Map<String, Object> product = productRow(saved.getId());
        assertThat(product.get("price")).isEqualTo(19_000); // cheapest option, not the ignored 99,999
        assertThat(product.get("original_price")).isNull(); // that cheapest option has no original price (not 123,456)
        assertThat(product.get("price_varies")).isEqualTo(true);
        assertThat(product.get("stock")).isEqualTo(0); // not the ignored 77
        assertThat(product.get("image_url")).isEqualTo(TWO_IMAGES.get(0));
        assertThat(jdbc.queryForList("SELECT sort_order || '|' || COALESCE(color_name,'-') || '|' || COALESCE(color_hex,'-') || '|' "
                + "|| COALESCE(size_label,'-') || '|' || price || '|' || COALESCE(original_price::text,'-') || '|' || stock "
                + "FROM product_options WHERE product_id = ? ORDER BY sort_order", String.class, saved.getId()))
                .containsExactly("0|화이트|#FFFFFF|M|20000|25000|4", "1|화이트|#ffffff|L|19000|-|6", "2|블랙|-|M|21000|30000|0");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT id) FROM product_options WHERE product_id = ?", Integer.class, saved.getId()))
                .isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT url FROM product_images WHERE product_id = ? ORDER BY sort_order", String.class, saved.getId()))
                .containsExactlyElementsOf(TWO_IMAGES);
    }

    @Test
    void 성공_최저가_옵션의_정가가_상품_정가가_되고_같은_최저가가_여럿이면_먼저_적은_옵션이_기준이다() {
        Product saved = create(List.of(
                no("화이트", null, "M", 10_000, 15_000, 1),
                no("화이트", null, "L", 10_000, 12_000, 1),
                no("블랙", null, "M", 12_000, 20_000, 1)));

        Map<String, Object> product = productRow(saved.getId());
        assertThat(product.get("price")).isEqualTo(10_000);
        assertThat(product.get("original_price")).isEqualTo(15_000);
        assertThat(product.get("price_varies")).isEqualTo(true);
    }

    @Test
    void 성공_모든_옵션_가격이_같으면_price_varies는_false이다() {
        Product saved = create(List.of(no(null, null, "S", 8_000, null, 3), no(null, null, "M", 8_000, null, 3)));

        Map<String, Object> product = productRow(saved.getId());
        assertThat(product.get("price")).isEqualTo(8_000);
        assertThat(product.get("price_varies")).isEqualTo(false);
        assertThat(product.get("original_price")).isNull();
    }

    @Test
    void 성공_옵션이_하나뿐이거나_0원_옵션도_만들어지고_옵션_50개_경계도_저장된다() {
        Product one = create(List.of(no("블랙", null, null, 0, 1, 0)));
        assertThat(productRow(one.getId())).containsEntry("price", 0).containsEntry("original_price", 1).containsEntry("price_varies", false);

        List<NewProductOption> fifty = IntStream.range(0, 50).mapToObj(i -> no(null, null, "S" + i, 1000 + i, null, i)).toList();
        Product many = create(fifty);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_options WHERE product_id = ?", Integer.class, many.getId())).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT max(sort_order) FROM product_options WHERE product_id = ?", Integer.class, many.getId())).isEqualTo(49);
        assertThat(productRow(many.getId())).containsEntry("price", 1000).containsEntry("price_varies", true);
    }

    @Test
    void 성공_옵션_이름은_공백을_자르고_30자까지_저장한다() {
        String thirty = "가".repeat(30);
        Product saved = create(List.of(no("  " + thirty + " ", null, " M ", 1000, null, 1)));

        assertThat(jdbc.queryForMap("SELECT color_name, size_label FROM product_options WHERE product_id = ?", saved.getId()))
                .containsEntry("color_name", thirty).containsEntry("size_label", "M");
    }

    @Test
    void 성공_옵션이_없거나_빈_목록이면_옛_방식으로_만들어지고_옵션_행은_없다() {
        for (List<NewProductOption> none : Arrays.asList(null, List.<NewProductOption>of())) {
            Product saved = productService.createProduct("옛 상품", "설명", 5_000, "https://legacy.example.com/a.png", null, 9, null,
                    7_000, null, none);

            assertThat(productRow(saved.getId())).containsEntry("price", 5_000).containsEntry("stock", 9)
                    .containsEntry("original_price", 7_000).containsEntry("price_varies", false);
        }
        assertThat(count("product_options")).isZero();
    }

    private Map<String, List<NewProductOption>> invalidOptionLists() {
        Map<String, List<NewProductOption>> cases = new LinkedHashMap<>();
        cases.put("색상 있는 옵션과 없는 옵션이 섞임", List.of(no("화이트", null, "M", 1000, null, 1), no(null, null, "L", 1000, null, 1)));
        cases.put("사이즈 있는 옵션과 없는 옵션이 섞임", List.of(no("화이트", null, "M", 1000, null, 1), no("블랙", null, null, 1000, null, 1)));
        cases.put("색상만 있는 옵션과 사이즈만 있는 옵션", List.of(no("화이트", null, null, 1000, null, 1), no(null, null, "M", 1000, null, 1)));
        cases.put("대소문자만 다른 중복", List.of(no("Red", null, "M", 1000, null, 1), no("red", null, "m", 1000, null, 1)));
        cases.put("공백과 대소문자만 다른 중복", List.of(no(" Red ", null, "M", 1000, null, 1), no("RED", null, "m", 1000, null, 1)));
        cases.put("사이즈만 있는 같은 옵션", List.of(no(null, null, "S", 1000, null, 1), no(null, null, " s ", 1000, null, 1)));
        cases.put("옵션 51개", IntStream.range(0, 51).mapToObj(i -> no(null, null, "S" + i, 1000, null, 1)).toList());
        cases.put("색상 코드 red", List.of(no("레드", "red", "M", 1000, null, 1)));
        cases.put("색상 코드 5자리", List.of(no("레드", "#12345", "M", 1000, null, 1)));
        cases.put("색상 코드 16진수 아님", List.of(no("레드", "#GGGGGG", "M", 1000, null, 1)));
        cases.put("색상 코드 7자리", List.of(no("레드", "#1234567", "M", 1000, null, 1)));
        cases.put("색상 코드에 # 없음", List.of(no("레드", "123456", "M", 1000, null, 1)));
        cases.put("색상 이름 없이 색상 코드", List.of(no(null, "#ffffff", "M", 1000, null, 1)));
        cases.put("정가가 판매가와 같음", List.of(no("레드", null, "M", 1000, 1000, 1)));
        cases.put("정가가 판매가보다 낮음", List.of(no("레드", null, "M", 1000, 999, 1)));
        cases.put("음수 가격", List.of(no("레드", null, "M", -1, null, 1)));
        cases.put("가격 없음", List.of(no("레드", null, "M", null, null, 1)));
        cases.put("음수 재고", List.of(no("레드", null, "M", 1000, null, -1)));
        cases.put("재고 없음", List.of(no("레드", null, "M", 1000, null, null)));
        cases.put("색상도 사이즈도 비어 있음", List.of(no("  ", null, "", 1000, null, 1)));
        cases.put("색상 이름 31자", List.of(no("가".repeat(31), null, "M", 1000, null, 1)));
        cases.put("사이즈 31자", List.of(no("레드", null, "가".repeat(31), 1000, null, 1)));
        cases.put("null 옵션 항목", Arrays.asList((NewProductOption) null));
        cases.put("두 번째 옵션만 잘못됨", List.of(no("레드", null, "M", 1000, null, 1), no("블루", "bad", "M", 1000, null, 1)));
        return cases;
    }

    @Test
    void 실패_잘못된_옵션이_있으면_검증_오류이고_상품_옵션_이미지가_하나도_저장되지_않는다() {
        for (Map.Entry<String, List<NewProductOption>> invalid : invalidOptionLists().entrySet()) {
            assertThatThrownBy(() -> create(invalid.getValue())).as(invalid.getKey()).isInstanceOf(ValidationException.class);
            assertThat(count("products")).as(invalid.getKey() + ": products").isZero();
            assertThat(count("product_options")).as(invalid.getKey() + ": options").isZero();
            assertThat(count("product_images")).as(invalid.getKey() + ": images").isZero();
        }
    }

    @Test
    void 실패_옵션은_정상이어도_상품_정보가_잘못되면_옵션과_이미지가_저장되지_않는다() {
        List<NewProductOption> good = List.of(no("레드", null, "M", 1000, null, 1));

        assertThatThrownBy(() -> productService.createProduct("", "설명", 1000, "https://x/a.png", TWO_IMAGES, 1, null, null, null, good))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> productService.createProduct("이름", "설명", 1000, "https://x/a.png", TWO_IMAGES, 1, "없는카테고리", null, null, good))
                .isInstanceOf(ValidationException.class);

        assertThat(count("products")).isZero();
        assertThat(count("product_options")).isZero();
        assertThat(count("product_images")).isZero();
    }

    @Test
    void 실패_두_번째_옵션_행_저장이_DB에서_실패하면_상품_옵션_이미지가_모두_남지_않는다() throws Exception {
        try {
            jdbc.execute("CREATE FUNCTION fail_second_option() RETURNS trigger AS $$ BEGIN "
                    + "IF NEW.sort_order = 1 THEN RAISE EXCEPTION 'forced option failure'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER trg_fail_second_option BEFORE INSERT ON product_options "
                    + "FOR EACH ROW EXECUTE FUNCTION fail_second_option()");

            assertThatThrownBy(() -> create(List.of(no("레드", null, "S", 1000, null, 1), no("레드", null, "M", 1000, null, 1),
                    no("레드", null, "L", 1000, null, 1)))).isInstanceOf(RuntimeException.class);

            assertThat(count("products")).isZero();
            assertThat(count("product_options")).isZero(); // the sort_order 0 row is gone too
            assertThat(count("product_images")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_fail_second_option ON product_options");
            jdbc.execute("DROP FUNCTION IF EXISTS fail_second_option()");
        }
    }

    @Test
    void 성공_옵션_상품의_관리자_목록_재고는_옵션_재고_합이고_옵션_없는_상품은_자기_재고다() {
        Product withOptions = create(List.of(no("레드", null, "S", 1000, null, 4), no("레드", null, "M", 1100, null, 6),
                no("레드", null, "L", 1200, null, 0)));
        Product plain = productService.createProduct("옛 상품", "설명", 5_000, "https://legacy.example.com/a.png", null, 9, null, null, null, null);
        Product allSoldOut = create(List.of(no(null, null, "S", 1000, null, 0)));

        Map<String, Integer> byId = new HashMap<>();
        ProductDtos.AdminProduct.from(productService.listAdminProducts(), productService.optionStockTotals())
                .forEach(p -> byId.put(p.id(), p.stock()));

        assertThat(byId).containsEntry(withOptions.getId(), 10).containsEntry(plain.getId(), 9).containsEntry(allSoldOut.getId(), 0);
    }

    @Test
    void 성공_상세의_options는_sort_order_순서이고_할인율과_가격차이를_담는다() {
        insertProductRow("p1");
        jdbc.update("UPDATE products SET price_varies = TRUE WHERE id = 'p1'");
        // insertion order and id order are both different from sort_order
        insertOption("a-id", "p1", "화이트", null, "L", 20_000, null, 5, 2);
        insertOption("c-id", "p1", "화이트", "#ffffff", "S", 20_000, 25_000, 7, 0);
        insertOption("b-id", "p1", "화이트", null, "M", 21_000, 21_001, 0, 1);
        Product p1 = productService.getProduct("p1").orElseThrow();

        ProductDtos.ProductDetail detail = ProductDtos.ProductDetail.from(p1, productService.getImageUrls(p1), productService.getOptions(p1));

        assertThat(detail.options()).extracting(ProductDtos.OptionView::id).containsExactly("c-id", "b-id", "a-id");
        assertThat(detail.options()).extracting(ProductDtos.OptionView::discountRate).containsExactly(20, null, null); // 21,000 -> 21,001 is under 1%
        assertThat(detail.options()).extracting(ProductDtos.OptionView::stock).containsExactly(7, 0, 5);
        assertThat(detail.options().get(0)).extracting(ProductDtos.OptionView::colorHex, ProductDtos.OptionView::originalPrice)
                .containsExactly("#ffffff", 25_000);
        assertThat(detail.priceVaries()).isTrue();
    }

    @Test
    void 성공_옵션_없는_상품의_상세_options는_빈_배열이고_priceVaries는_false이다() {
        insertProductRow("p1");
        Product p1 = productService.getProduct("p1").orElseThrow();

        ProductDtos.ProductDetail detail = ProductDtos.ProductDetail.from(p1, productService.getImageUrls(p1), productService.getOptions(p1));

        assertThat(detail.options()).isEmpty();
        assertThat(detail.priceVaries()).isFalse();
    }

    @Test
    void 성공_목록의_priceVaries는_가격이_다른_옵션_상품만_true이고_옵션_행은_다른_상품과_섞이지_않는다() {
        Product varies = create(List.of(no(null, null, "S", 1000, null, 1), no(null, null, "M", 2000, null, 1)));
        Product same = create(List.of(no(null, null, "S", 1500, null, 1), no(null, null, "M", 1500, null, 1)));
        Product plain = productService.createProduct("옛 상품", "설명", 5_000, "https://legacy.example.com/a.png", null, 9, null, null, null, null);

        Map<String, Boolean> flags = new HashMap<>();
        ProductDtos.ProductListItem.from(productService.listProducts(null, ProductSort.NEWEST, 0, 20).getContent())
                .forEach(item -> flags.put(item.id(), item.priceVaries()));

        assertThat(flags).containsEntry(varies.getId(), true).containsEntry(same.getId(), false).containsEntry(plain.getId(), false);
        assertThat(productService.getOptions(varies)).hasSize(2);
        assertThat(productService.getOptions(same)).hasSize(2).extracting(ProductOption::getProductId).containsOnly(same.getId());
        assertThat(productService.getOptions(plain)).isEmpty();
    }

    @Test
    void 성공_서비스로_만든_옵션_상품을_진짜_옵션_id로_주문하면_옵션_재고만_줄고_옵션_가격으로_계산한다() {
        Product saved = create(List.of(no("화이트", "#ffffff", "M", 20_000, 25_000, 10), no("화이트", null, "L", 30_000, null, 5)));
        List<ProductOption> options = productService.getOptions(saved);
        String optionL = options.get(1).getId();

        Placed p = place(data.user(), opt(saved.getId(), optionL, 2));

        assertThat(p.total()).isEqualTo(60_000); // 2 x 30,000, over the free shipping line
        assertThat(stocks()).containsEntry("o:" + options.get(0).getId(), 10).containsEntry("o:" + optionL, 3)
                .containsEntry("p:" + saved.getId(), 0);
        assertThat(itemRows(p.orderId())).containsExactly(saved.getId() + "|" + optionL + "|화이트 / L|2|30000");
    }

    // ================================================================== 3. orders on option products

    @Test
    void 성공_옵션_주문은_옵션_재고만_줄이고_옵션_가격과_이름_스냅샷_합계로_저장한다() {
        catalog();
        Map<String, Integer> before = baseline();

        Placed p = place(data.user(), opt("pB", "pB-1", 2));

        assertThat(stocks()).isEqualTo(but(before, O_B1, 8)); // product pB stays 0, pB-2 and the legacy products untouched
        assertThat(p.total()).isEqualTo(2 * 20_000 + 3_500);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, p.orderId())).isEqualTo(43_500);
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM orders WHERE id = ?", Integer.class, p.orderId())).isEqualTo(3_500);
        assertThat(itemRows(p.orderId())).containsExactly("pB|pB-1|화이트 / M|2|20000");
    }

    @Test
    void 성공_옵션_이름과_가격은_주문_뒤에_옵션이_바뀌어도_주문_기록에_그대로_남는다() {
        catalog();
        Placed p = place(data.user(), opt("pB", "pB-1", 1));

        jdbc.update("UPDATE product_options SET price = 1, color_name = '변경', size_label = 'XL' WHERE id = 'pB-1'");

        assertThat(itemRows(p.orderId())).containsExactly("pB|pB-1|화이트 / M|1|20000");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM orders WHERE id = ?", Integer.class, p.orderId())).isEqualTo(23_500);
    }

    @Test
    void 성공_사이즈만_있는_상품과_색상만_있는_상품의_이름_스냅샷과_무료배송_경계는_옵션_가격으로_계산한다() {
        optionProduct("pE", 25_000);
        option("e-1", "pE", null, "S", 49_999, 10, 0);
        option("e-2", "pE", null, "M", 50_000, 10, 1);
        option("e-3", "pE", null, "L", 25_000, 10, 2);
        optionProduct("pF", 10_000);
        option("f-1", "pF", "블랙", null, 10_000, 10, 0);
        String user = data.user();

        Placed belowLine = place(user, opt("pE", "e-1", 1));
        Placed onLine = place(user, opt("pE", "e-2", 1));
        Placed twoOptionUnits = place(user, opt("pE", "e-3", 2));
        Placed mixed = place(user, opt("pE", "e-3", 1), opt("pF", "f-1", 2)); // 25,000 + 20,000 = 45,000

        assertThat(belowLine.total()).isEqualTo(49_999 + 3_500);
        assertThat(onLine.total()).isEqualTo(50_000);
        assertThat(twoOptionUnits.total()).isEqualTo(50_000);
        assertThat(mixed.total()).isEqualTo(45_000 + 3_500);
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM orders WHERE id = ?", Integer.class, onLine.orderId())).isZero();
        assertThat(itemRows(belowLine.orderId())).containsExactly("pE|e-1|S|1|49999");
        assertThat(itemRows(mixed.orderId())).containsExactlyInAnyOrder("pE|e-3|L|1|25000", "pF|f-1|블랙|2|10000");
        assertThat(stocks()).containsEntry("o:e-1", 9).containsEntry("o:e-2", 9).containsEntry("o:e-3", 7).containsEntry("o:f-1", 8)
                .containsEntry("p:pE", 0).containsEntry("p:pF", 0);
    }

    @Test
    void 성공_일반_상품과_옵션_상품이_섞인_주문은_줄마다_맞는_테이블의_재고를_줄인다() {
        catalog();
        Map<String, Integer> before = baseline();

        Placed p = place(data.user(), line("pC", 1), opt("pB", "pB-2", 2), line("pA", 2), opt("pB", "pB-1", 1));

        assertThat(stocks()).isEqualTo(afterFull(before));
        assertThat(p.total()).isEqualTo(FULL_TOTAL);
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM orders WHERE id = ?", Integer.class, p.orderId())).isZero();
        assertThat(itemRows(p.orderId())).containsExactlyInAnyOrder("pA|-|-|2|1000", "pB|pB-1|화이트 / M|1|20000",
                "pB|pB-2|화이트 / L|2|25000", "pC|-|-|1|3000");
    }

    @Test
    void 성공_같은_옵션을_여러_줄로_보내면_한_줄로_합쳐서_한_번에_차감한다() {
        catalog();
        Map<String, Integer> before = baseline();

        Placed p = place(data.user(), opt("pB", "pB-1", 1), opt("pB", "pB-1", 2), opt("pB", "pB-2", 1));

        assertThat(stocks()).isEqualTo(but(before, O_B1, 7, O_B2, 4));
        assertThat(itemRows(p.orderId())).containsExactlyInAnyOrder("pB|pB-1|화이트 / M|3|20000", "pB|pB-2|화이트 / L|1|25000");
    }

    @Test
    void 성공_같은_상품의_두_옵션은_따로_합산해서_하나가_모자라면_그_옵션_이름으로_거절한다() {
        catalog();
        Snap before = snap();

        // pB-1 has 10, pB-2 has 5: 4 + 4 of pB-2 = 8 > 5, even though 8 < 10 (the other option's stock does not help)
        assertThatThrownBy(() -> place(data.user(), opt("pB", "pB-2", 4), opt("pB", "pB-2", 4), opt("pB", "pB-1", 1)))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("화이트 / L");
        assertThat(snap()).isEqualTo(before);
    }

    private void assertRefusedUntouched(Class<? extends Throwable> type, OrderLine... lines) {
        Snap before = snap();
        assertThatThrownBy(() -> place(data.user(), lines)).isInstanceOf(type);
        assertThat(snap()).isEqualTo(before);
    }

    @Test
    void 실패_옵션이_있는_상품을_옵션_없이_주문하면_거절하고_아무것도_바뀌지_않는다() {
        catalog();

        assertRefusedUntouched(ValidationException.class, opt("pB", null, 1));
        assertRefusedUntouched(ValidationException.class, opt("pB", "", 1));
        assertRefusedUntouched(ValidationException.class, opt("pB", "   ", 1));
        assertRefusedUntouched(ValidationException.class, line("pA", 1), line("pB", 1));
        assertThatThrownBy(() -> place(data.user(), line("pB", 1))).hasMessageContaining("옵션을 선택");
    }

    @Test
    void 실패_옵션이_없는_상품에_optionId를_보내면_거절하고_아무것도_바뀌지_않는다() {
        catalog();

        assertRefusedUntouched(ValidationException.class, opt("pA", "pB-1", 1)); // a real option, but of another product
        assertRefusedUntouched(ValidationException.class, opt("pA", "no-such-option", 1));
        assertRefusedUntouched(ValidationException.class, opt("pC", "pB-2", 1), line("pA", 1));
    }

    @Test
    void 실패_다른_상품의_옵션_id나_없는_옵션_id로_옵션_상품을_주문하면_거절하고_아무것도_바뀌지_않는다() {
        catalog();
        optionProduct("pD", 1000);
        option("pD-1", "pD", "블랙", "M", 1000, 5, 0);

        assertRefusedUntouched(ValidationException.class, opt("pB", "pD-1", 1)); // pD's option on pB
        assertRefusedUntouched(ValidationException.class, opt("pD", "pB-1", 1));
        assertRefusedUntouched(ValidationException.class, opt("pB", "ghost", 1));
        assertRefusedUntouched(ValidationException.class, opt("pB", "pB-1", 1), opt("pB", "pD-1", 1)); // one good line does not rescue a bad one
        assertThatThrownBy(() -> place(data.user(), opt("pB", "pD-1", 1))).hasMessageContaining("옵션");
    }

    @Test
    void 실패_없는_상품이면_옵션_id가_있어도_상품_없음이다() {
        catalog();

        assertRefusedUntouched(ProductNotFoundException.class, opt("ghost-product", "pB-1", 1));
        assertRefusedUntouched(ProductNotFoundException.class, opt("pB", "pB-1", 1), line("ghost-product", 1));
    }

    @Test
    void 실패_옵션_재고가_모자라면_옵션_이름이_들어간_재고_부족이고_아무것도_바뀌지_않는다() {
        catalog();
        Snap before = snap();

        assertThatThrownBy(() -> place(data.user(), opt("pB", "pB-2", 6)))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("화이트 / L").hasMessageContaining("상품 pB");
        assertThat(snap()).isEqualTo(before);

        // the boundary: exactly the stock sells, one more does not
        place(data.user(), opt("pB", "pB-2", 5));
        assertThat(stocks()).isEqualTo(but(before.stocks(), O_B2, 0));
        assertThatThrownBy(() -> place(data.user(), opt("pB", "pB-2", 1))).isInstanceOf(InsufficientStockException.class);
        assertThat(stocks()).isEqualTo(but(before.stocks(), O_B2, 0));
    }

    @Test
    void 실패_다른_옵션과_일반_상품의_재고가_충분해도_모자란_옵션_하나가_있으면_전체가_거절된다() {
        catalog();
        Snap before = snap();

        assertThatThrownBy(() -> place(data.user(), line("pA", 1), opt("pB", "pB-1", 1), opt("pB", "pB-2", 6), line("pC", 1)))
                .isInstanceOf(InsufficientStockException.class).hasMessageContaining("화이트 / L");
        assertThat(snap()).isEqualTo(before);

        // sold-out legacy line among healthy option lines
        setStock(P_C, 0);
        Snap beforeLegacy = snap();
        assertThatThrownBy(() -> place(data.user(), opt("pB", "pB-1", 1), line("pC", 1))).isInstanceOf(InsufficientStockException.class);
        assertThat(snap()).isEqualTo(beforeLegacy);
    }

    @Test
    void 실패_뒤쪽_옵션의_갱신이_뒤늦게_실패하면_앞서_차감한_일반_상품과_옵션도_함께_되돌려진다() throws Exception {
        catalog();
        Snap before = snap();

        // pA and pB-1 are decremented first (StockKey order); the trigger then empties pB-2 so that its own update finds 0 rows
        withUpdateTrigger("product_options",
                "BEGIN IF NEW.id = 'pB-1' THEN UPDATE product_options SET stock = 0 WHERE id = 'pB-2'; END IF; RETURN NEW; END;",
                () -> assertThatThrownBy(() -> place(data.user(), line("pA", 1), opt("pB", "pB-1", 1), opt("pB", "pB-2", 1)))
                        .isInstanceOf(InsufficientStockException.class).hasMessageContaining("화이트 / L"));

        assertThat(snap()).isEqualTo(before); // pA, pB-1 and pB-2 are all back
    }

    // ================================================================== 4. idempotency

    @Test
    void 성공_같은_키와_같은_옵션_줄로_다시_주문하면_같은_주문을_돌려주고_재고는_더_줄지_않는다() {
        catalog();
        Placed p = place(data.user(), fullLines());
        Snap afterOrder = snap();
        Map<String, Object> rowBefore = orderRow(p.orderId());

        Order again = replay(p);
        // the same lines listed in another order and split into more lines are the same request
        Order reordered = orderService.createOrder(p.userId(), p.key(), NAME, PHONE, ADDR,
                List.of(line("pC", 1), opt("pB", "pB-2", 1), opt("pB", "pB-2", 1), opt("pB", "pB-1", 1), line("pA", 2)));

        assertThat(again.getId()).isEqualTo(p.orderId());
        assertThat(reordered.getId()).isEqualTo(p.orderId());
        assertThat(snap()).isEqualTo(afterOrder);
        assertThat(orderRow(p.orderId())).isEqualTo(rowBefore);
        assertThat(itemRows(p.orderId())).hasSize(4);
    }

    @Test
    void 실패_같은_키에_옵션만_바꾸면_키_재사용_오류이고_재고와_주문은_그대로다() {
        catalog();
        Placed p = place(data.user(), opt("pB", "pB-1", 1));
        Snap afterOrder = snap();

        for (OrderLine[] different : List.of(
                new OrderLine[]{opt("pB", "pB-2", 1)},                      // other option, same product and quantity
                new OrderLine[]{opt("pB", "pB-1", 2)},                      // same option, other quantity
                new OrderLine[]{opt("pB", "pB-1", 1), line("pA", 1)},       // one more line
                new OrderLine[]{line("pB", 1)})) {                          // option dropped
            assertThatThrownBy(() -> orderService.createOrder(p.userId(), p.key(), NAME, PHONE, ADDR, List.of(different)))
                    .isInstanceOf(IdempotencyKeyReuseException.class);
        }
        assertThat(snap()).isEqualTo(afterOrder);
        assertThat(replay(p).getId()).isEqualTo(p.orderId()); // the original request still replays
    }

    @Test
    void 성공_옵션_없는_주문의_요청_해시는_옵션_기능_이전과_같은_값으로_저장된다() {
        legacy("p1", 10, 1000);
        legacy("pA", 10, 1000);
        legacy("pB", 10, 1000);
        String user = data.user();

        Placed one = place(user, line("p1", 2));
        Placed two = place(user, line("pB", 3), line("pA", 1));
        Placed blankOption = place(user, opt("p1", "", 1), opt("p1", "  ", 1)); // "" and blank mean no option: 1 + 1 of p1
        Placed splitLegacy = place(user, line("p1", 1), line("p1", 1));

        assertThat(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, one.orderId())).isEqualTo(LEGACY_ONE_LINE);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, two.orderId())).isEqualTo(LEGACY_TWO_LINES);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, splitLegacy.orderId())).isEqualTo(LEGACY_ONE_LINE);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, blankOption.orderId()))
                .isEqualTo(LEGACY_ONE_LINE);
        assertThat(itemRows(blankOption.orderId())).containsExactly("p1|-|-|2|1000");
    }

    @Test
    void 성공_옵션_주문의_해시는_옵션_없는_주문과_달라서_같은_키로_섞어_쓸_수_없다() {
        catalog();
        String user = data.user();
        Placed optionOrder = place(user, opt("pB", "pB-1", 2));
        Placed legacyOrder = place(user, line("pA", 2));

        assertThat(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, optionOrder.orderId()))
                .hasSize(64).isNotEqualTo(jdbc.queryForObject("SELECT request_hash FROM orders WHERE id = ?", String.class, legacyOrder.orderId()));
        assertThatThrownBy(() -> orderService.createOrder(user, optionOrder.key(), NAME, PHONE, ADDR, List.of(line("pA", 2))))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void 성공_같은_키의_옵션_주문_요청을_동시에_보내도_주문은_하나이고_옵션_재고는_한_번만_줄어든다() throws Exception {
        for (int round = 0; round < 8; round++) {
            resetWithCatalog();
            Map<String, Integer> before = baseline();
            String user = data.user();
            String key = "key-" + UUID.randomUUID();

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> placeWithKey(user, key, opt("pB", "pB-1", 2), line("pA", 1)).orderId());
            }
            List<Object> results = runTogether(tasks);

            assertNoThrowable(results, "round " + round);
            assertThat(results.stream().distinct().count()).as("round %d: one order id", round).isEqualTo(1);
            assertThat(count("orders")).as("round %d", round).isEqualTo(1);
            assertThat(stocks()).as("round %d", round).isEqualTo(but(before, O_B1, 8, P_A, 9));
        }
    }

    // ================================================================== 5. oversell races

    @Test
    void 성공_한_옵션의_마지막_재고를_4명이_동시에_주문하면_재고만큼만_팔리고_음수가_되지_않는다() throws Exception {
        int[][] scenarios = {{1, 1, 1}, {3, 1, 3}, {5, 2, 2}, {4, 1, 4}, {2, 3, 0}}; // stock, quantity per order, orders that fit
        for (int[] s : scenarios) {
            for (int round = 0; round < 5; round++) {
                resetWithCatalog();
                setStock(O_B1, s[0]);
                Map<String, Integer> before = baseline();
                String context = "stock " + s[0] + " x" + s[1] + " round " + round;

                List<OrderLine[]> requests = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    requests.add(new OrderLine[]{opt("pB", "pB-1", s[1])});
                }
                List<Object> results = raceOrders(requests);

                List<Throwable> failures = assertOnlyInsufficientStock(results, 4 - s[2], context);
                assertThat(failures).allSatisfy(t -> assertThat(t).hasMessageContaining("화이트 / M"));
                assertThat(stocks()).as(context).isEqualTo(but(before, O_B1, s[0] - s[2] * s[1])); // never negative, product stock untouched
                assertThat(count("orders")).as(context).isEqualTo(s[2]);
                assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE option_id = 'pB-1'", Integer.class))
                        .as(context).isEqualTo(s[2] * s[1]);
            }
        }
    }

    @Test
    void 성공_같은_상품의_다른_옵션_주문은_서로의_재고에_영향을_주지_않고_각자_재고만큼_팔린다() throws Exception {
        int[][] scenarios = {{1, 1}, {2, 1}, {0, 2}}; // stock of pB-1, stock of pB-2; two threads on each option, 1 unit each
        for (int[] s : scenarios) {
            for (int round = 0; round < 8; round++) {
                resetWithCatalog();
                setStock(O_B1, s[0]);
                setStock(O_B2, s[1]);
                Map<String, Integer> before = baseline();
                String context = "stocks " + s[0] + "/" + s[1] + " round " + round;

                List<Object> results = raceOrders(List.of(
                        new OrderLine[]{opt("pB", "pB-1", 1)}, new OrderLine[]{opt("pB", "pB-1", 1)},
                        new OrderLine[]{opt("pB", "pB-2", 1)}, new OrderLine[]{opt("pB", "pB-2", 1)}));

                int soldB1 = Math.min(2, s[0]);
                int soldB2 = Math.min(2, s[1]);
                assertOnlyInsufficientStock(results, 4 - soldB1 - soldB2, context);
                assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE option_id = 'pB-1'", Integer.class))
                        .as(context + " pB-1 sold").isEqualTo(soldB1);
                assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM order_items WHERE option_id = 'pB-2'", Integer.class))
                        .as(context + " pB-2 sold").isEqualTo(soldB2);
                assertThat(stocks()).as(context).isEqualTo(but(before, O_B1, s[0] - soldB1, O_B2, s[1] - soldB2));
            }
        }
    }

    @Test
    void 성공_일반_상품과_옵션을_같이_담은_주문이_동시에_와도_모자란_쪽이_기준이고_실패한_주문의_차감은_되돌려진다() throws Exception {
        int[][] scenarios = {{3, 2, 2}, {1, 5, 1}, {2, 2, 2}, {4, 1, 1}}; // pA stock, pB-1 stock, orders that fit
        for (int[] s : scenarios) {
            for (int round = 0; round < 8; round++) {
                resetWithCatalog();
                setStock(P_A, s[0]);
                setStock(O_B1, s[1]);
                Map<String, Integer> before = baseline();
                String context = "pA " + s[0] + ", pB-1 " + s[1] + " round " + round;

                List<OrderLine[]> requests = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    requests.add(new OrderLine[]{opt("pB", "pB-1", 1), line("pA", 1)});
                }
                List<Object> results = raceOrders(requests);

                assertOnlyInsufficientStock(results, 4 - s[2], context);
                assertThat(count("orders")).as(context).isEqualTo(s[2]);
                // the failed orders had decremented the first line (pA) before the second one failed: that must be undone
                assertThat(stocks()).as(context).isEqualTo(but(before, P_A, s[0] - s[2], O_B1, s[1] - s[2]));
            }
        }
    }

    // ================================================================== 6. lock order / deadlock

    private static final List<OrderLine[]> OPPOSITE_ORDERS = List.of(
            new OrderLine[]{line("pA", 1), opt("pB", "pB-1", 1), opt("pB", "pB-2", 1), line("pC", 1)},
            new OrderLine[]{line("pC", 1), opt("pB", "pB-2", 1), opt("pB", "pB-1", 1), line("pA", 1)},
            new OrderLine[]{opt("pB", "pB-2", 1), line("pA", 1), line("pC", 1), opt("pB", "pB-1", 1)},
            new OrderLine[]{opt("pB", "pB-1", 1), line("pC", 1), line("pA", 1), opt("pB", "pB-2", 1)});

    private void bigStocks() {
        for (String key : List.of(P_A, O_B1, O_B2, P_C)) {
            setStock(key, 1000);
        }
    }

    @Test
    void 성공_같은_줄을_서로_반대_순서로_담은_주문이_행_잠금을_오래_잡아도_교착_없이_모두_성공한다() throws Exception {
        catalog();
        bigStocks();
        Map<String, Integer> before = baseline();
        int rounds = 8;

        withSlowStockUpdates(() -> {
            for (int round = 0; round < rounds; round++) {
                assertNoThrowable(raceOrders(OPPOSITE_ORDERS.subList(0, 2)), "round " + round);
            }
        });

        int orders = rounds * 2;
        assertThat(count("orders")).isEqualTo(orders);
        assertThat(stocks()).isEqualTo(but(before, P_A, 1000 - orders, O_B1, 1000 - orders, O_B2, 1000 - orders, P_C, 1000 - orders));
    }

    @Test
    void 성공_네_가지_다른_순서의_주문_네_개가_동시에_와도_교착_없이_모두_성공한다() throws Exception {
        catalog();
        bigStocks();
        Map<String, Integer> before = baseline();
        int rounds = 60;

        for (int round = 0; round < rounds; round++) {
            assertNoThrowable(raceOrders(OPPOSITE_ORDERS), "round " + round);
        }

        int orders = rounds * 4;
        assertThat(count("orders")).isEqualTo(orders);
        assertThat(stocks()).isEqualTo(but(before, P_A, 1000 - orders, O_B1, 1000 - orders, O_B2, 1000 - orders, P_C, 1000 - orders));
    }

    @Test
    void 성공_같은_상품의_두_옵션만_반대_순서로_담은_주문도_잠금을_오래_잡아도_교착_없이_성공한다() throws Exception {
        catalog();
        bigStocks();
        Map<String, Integer> before = baseline();
        int rounds = 8;

        withSlowStockUpdates(() -> {
            for (int round = 0; round < rounds; round++) {
                assertNoThrowable(raceOrders(List.of(
                        new OrderLine[]{opt("pB", "pB-1", 1), opt("pB", "pB-2", 1)},
                        new OrderLine[]{opt("pB", "pB-2", 1), opt("pB", "pB-1", 1)})), "round " + round);
            }
        });

        assertThat(stocks()).isEqualTo(but(before, O_B1, 1000 - rounds * 2, O_B2, 1000 - rounds * 2));
    }

    @Test
    void 성공_주문_만료_늦은_결제_재확보가_반대_순서_주문과_동시에_돌아도_교착_없이_재고가_맞는다() throws Exception {
        for (int round = 0; round < 4; round++) {
            resetWithCatalog();
            bigStocks();
            Map<String, Integer> before = baseline();
            String buyer = data.user();
            OrderLine[] forward = OPPOSITE_ORDERS.get(0);
            OrderLine[] backward = OPPOSITE_ORDERS.get(1);
            Placed lateOne = expired(buyer, forward);          // stock given back, will be reserved again by the late payment
            providerPaid(lateOne);
            Placed overdueOne = place(buyer, backward);
            Placed overdueTwo = place(buyer, forward);
            overdue(overdueOne.orderId());
            overdue(overdueTwo.orderId());
            String context = "round " + round;

            withSlowStockUpdates(() -> {
                List<Object> results = runTogether(List.of(
                        () -> paymentService.expirePending(),
                        () -> paymentService.complete(lateOne.orderId(), buyer),
                        () -> place(buyer, backward).orderId(),
                        () -> place(buyer, forward).orderId()));
                assertNoThrowable(results, context);
            });

            assertThat(status(lateOne.orderId())).as(context).isEqualTo("PAID");
            assertThat(status(overdueOne.orderId())).as(context).isEqualTo("EXPIRED");
            assertThat(status(overdueTwo.orderId())).as(context).isEqualTo("EXPIRED");
            // late payment (-1 each) + two new orders (-2 each); the two expired orders are net 0
            int expected = 1000 - 3;
            assertThat(stocks()).as(context).isEqualTo(but(before, P_A, expected, O_B1, expected, O_B2, expected, P_C, expected));
        }
    }

    // ================================================================== 7. expiry and restore

    @Test
    void 성공_기한이_지난_대기_주문을_정리하면_일반_상품은_상품에_옵션은_옵션에_정확히_한_번_돌아온다() {
        catalog();
        Map<String, Integer> before = baseline();
        Placed p = place(data.user(), fullLines());
        assertThat(stocks()).isEqualTo(afterFull(before));
        overdue(p.orderId());

        assertThat(paymentService.expirePending()).isEqualTo(1);

        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stocks()).isEqualTo(before);
        // again and again: nothing is restored twice
        assertThat(paymentService.expirePending()).isZero();
        assertThat(transactions.expireOne(p.orderId())).isFalse();
        assertThat(stocks()).isEqualTo(before);
    }

    @Test
    void 성공_여러_스레드가_동시에_정리해도_옵션과_일반_상품_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 10; round++) {
            resetWithCatalog();
            Map<String, Integer> before = baseline();
            Placed p = place(data.user(), fullLines());
            overdue(p.orderId());

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> paymentService.expirePending());
            }
            List<Object> results = runTogether(tasks);

            assertNoThrowable(results, "round " + round);
            assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).as("round %d: orders expired in total", round).isEqualTo(1);
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("EXPIRED");
            assertThat(stocks()).as("round %d", round).isEqualTo(before);
        }
    }

    @Test
    void 성공_PAID_주문은_기한이_지나도_정리되지_않고_재고도_돌아오지_않는다() {
        catalog();
        Placed p = paid(data.user(), fullLines());
        overdue(p.orderId());
        Snap afterPayment = snap();

        assertThat(paymentService.expirePending()).isZero();
        assertThat(transactions.expireOne(p.orderId())).isFalse();

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(snap()).isEqualTo(afterPayment);
    }

    @Test
    void 성공_기한이_지난_옵션_주문을_같은_키로_다시_보내면_EXPIRED로_돌려주고_옵션_재고를_한_번만_되돌린다() {
        catalog();
        Map<String, Integer> before = baseline();
        Placed p = place(data.user(), fullLines());
        overdue(p.orderId());

        Order replayed = replay(p);

        assertThat(replayed.getId()).isEqualTo(p.orderId());
        assertThat(replayed.getStatus()).isEqualTo("EXPIRED");
        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stocks()).isEqualTo(before);

        assertThat(replay(p).getStatus()).isEqualTo("EXPIRED");
        assertThat(paymentService.expirePending()).isZero();
        assertThat(stocks()).as("not restored twice").isEqualTo(before);
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    void 성공_기한이_지나지_않은_옵션_주문의_재생은_재고를_바꾸지_않는다() {
        catalog();
        Placed p = place(data.user(), fullLines());
        Snap afterOrder = snap();

        assertThat(replay(p).getStatus()).isEqualTo("PENDING_PAYMENT");

        assertThat(snap()).isEqualTo(afterOrder);
    }

    @Test
    void 성공_재생이_행_잠금을_기다리는_동안_결제가_확정되면_옵션_재고를_되돌리지_않고_PAID로_돌려준다() throws Exception {
        for (int round = 0; round < 4; round++) {
            resetWithCatalog();
            Map<String, Integer> before = baseline();
            Placed p = place(data.user(), fullLines());
            overdue(p.orderId());

            ExecutorService pool = Executors.newSingleThreadExecutor();
            Order replayed;
            try (RowHolder holder = new RowHolder(p.orderId())) {
                Future<Order> worker = pool.submit(() -> replay(p)); // loads the PENDING copy, then queues on the row lock
                awaitBlockedBackend();
                holder.markPaidAndCommit(p.orderId());

                replayed = worker.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("PAID");
            assertThat(replayed.getStatus()).as("round %d: returned status", round).isEqualTo("PAID");
            assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId()))
                    .as("round %d", round).isEqualTo("tx-holder");
            assertThat(stocks()).as("round %d: nothing restored for a paid order", round).isEqualTo(afterFull(before));
        }
    }

    @Test
    void 성공_재생이_기다리던_잠금이_결제_없이_풀리면_그때_만료시키고_옵션과_일반_재고를_되돌린다() throws Exception {
        catalog();
        Map<String, Integer> before = baseline();
        Placed p = place(data.user(), fullLines());
        overdue(p.orderId());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Order> worker;
            try (RowHolder holder = new RowHolder(p.orderId())) {
                worker = pool.submit(() -> replay(p));
                awaitBlockedBackend();
            } // closed = rolled back: nothing changed
            assertThat(worker.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("EXPIRED");
        } finally {
            pool.shutdownNow();
        }

        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stocks()).isEqualTo(before);
    }

    // ================================================================== 8. payment flows with option lines

    @Test
    void 성공_결제를_확인하면_PAID가_되고_옵션과_일반_재고는_그대로이며_웹훅도_같은_결과다() throws Exception {
        catalog();
        String user = data.user();
        Placed viaBrowser = place(user, fullLines());
        Placed viaWebhook = place(user, opt("pB", "pB-1", 1), line("pA", 1));
        Snap afterOrders = snap();
        providerPaid(viaBrowser);
        providerPaid(viaWebhook);

        assertThat(paymentService.complete(viaBrowser.orderId(), user)).isEqualTo("PAID");
        webhook("Transaction.Paid", viaWebhook.orderId());

        assertThat(status(viaBrowser.orderId())).isEqualTo("PAID");
        assertThat(status(viaWebhook.orderId())).isEqualTo("PAID");
        assertThat(snap()).isEqualTo(afterOrders);
    }

    @Test
    void 실패_옵션_주문의_결제_금액이_옵션_가격_합계와_다르면_거부하고_주문과_재고는_그대로다() {
        catalog();
        String user = data.user();
        Placed p = place(user, opt("pB", "pB-1", 1)); // 20,000 + 3,500
        Snap afterOrder = snap();
        Map<String, Object> rowBefore = orderRow(p.orderId());

        for (long wrong : new long[]{20_000L, 23_499L, 23_501L, 1_000L + 3_500L}) { // item price only, off by one, the legacy price
            gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", wrong, "KRW", "tx", "LIVE"));
            assertThatThrownBy(() -> paymentService.complete(p.orderId(), user))
                    .isInstanceOf(com.shop.backend.domain.error.PaymentAmountMismatchException.class);
        }
        assertThat(orderRow(p.orderId())).isEqualTo(rowBefore);
        assertThat(snap()).isEqualTo(afterOrder);

        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", 23_500L, "KRW", "tx", "LIVE"));
        assertThat(paymentService.complete(p.orderId(), user)).isEqualTo("PAID");
    }

    @Test
    void 성공_정리된_주문에_결제가_확인되면_옵션과_일반_재고가_정확히_한_번만_다시_차감된다() throws Exception {
        catalog();
        Map<String, Integer> before = baseline();
        String user = data.user();
        Placed p = expired(user, fullLines());
        assertThat(stocks()).isEqualTo(before);
        providerPaid(p);

        assertThat(paymentService.complete(p.orderId(), user)).isEqualTo("PAID");

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stocks()).isEqualTo(afterFull(before));

        // repeated confirmations (browser again, webhook, DB transition) and the sweeper change nothing
        paymentService.complete(p.orderId(), user);
        webhook("Transaction.Paid", p.orderId());
        transactions.applyPaid(p.orderId(), "tx-again");
        paymentService.expirePending();
        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stocks()).isEqualTo(afterFull(before));
    }

    @Test
    void 성공_정리된_주문의_옵션_재고가_딱_필요한_만큼이면_PAID가_되고_그_옵션_재고는_0이_된다() {
        catalog();
        String user = data.user();
        Placed p = expired(user, opt("pB", "pB-2", 3));
        setStock(O_B2, 3); // other buyers took the rest
        providerPaid(p);
        Map<String, Integer> beforePayment = baseline();

        assertThat(paymentService.complete(p.orderId(), user)).isEqualTo("PAID");

        assertThat(stocks()).isEqualTo(but(beforePayment, O_B2, 0));
    }

    /** The late payment of a full order finds {@code shortKey} too small: nothing may stay reserved, in any table. */
    @ParameterizedTest
    @ValueSource(strings = {P_A, O_B1, O_B2, P_C})
    void 실패_정리된_주문의_한_줄_재고가_모자라면_PAYMENT_REVIEW이고_앞서_잡은_재고는_옵션과_일반_모두_되돌려진다(String shortKey) throws Exception {
        catalog();
        String user = data.user();
        Placed p = expired(user, fullLines());
        // enough for every line, except the short one
        setStock(P_A, 5);
        setStock(O_B1, 5);
        setStock(O_B2, 5);
        setStock(P_C, 5);
        setStock(shortKey, 0);
        Map<String, Integer> afterExpiry = baseline();
        providerPaid(p);

        assertThatThrownBy(() -> paymentService.complete(p.orderId(), user)).isInstanceOf(PaymentNeedsReviewException.class);

        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(jdbc.queryForObject("SELECT paid_at FROM orders WHERE id = ?", Timestamp.class, p.orderId())).isNull();
        assertThat(stocks()).as("short row %s: everything reserved before it is back", shortKey).isEqualTo(afterExpiry);
        // asking again, the webhook and the sweeper leave it alone
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), user)).isInstanceOf(PaymentNeedsReviewException.class);
        webhook("Transaction.Paid", p.orderId());
        assertThat(paymentService.expirePending()).isZero();
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(stocks()).isEqualTo(afterExpiry);
    }

    @Test
    void 성공_정리된_주문의_늦은_결제가_마지막_옵션_재고를_두고_다른_주문과_경쟁해도_초과_판매되지_않는다() throws Exception {
        for (int round = 0; round < 15; round++) {
            resetWithCatalog();
            setStock(O_B1, 1);
            String buyer = data.user();
            Placed late = expired(buyer, opt("pB", "pB-1", 1), line("pA", 1)); // stock 1 again
            providerPaid(late);
            String other = data.user();
            Map<String, Integer> before = baseline();

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(late.orderId(), buyer),
                    () -> place(other, opt("pB", "pB-1", 1)).orderId()));

            String lateStatus = status(late.orderId());
            Integer otherOrders = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, other);
            assertThat(stocks().get(O_B1)).as("round %d: never negative, the one unit is gone", round).isZero();
            if ("PAID".equals(lateStatus)) {
                assertThat(otherOrders).as("round %d: the other order must have failed", round).isZero();
                assertThat(results.get(1)).isInstanceOf(InsufficientStockException.class);
                assertThat(stocks()).as("round %d", round).isEqualTo(but(before, O_B1, 0, P_A, before.get(P_A) - 1));
            } else {
                assertThat(lateStatus).as("round %d", round).isEqualTo("PAYMENT_REVIEW");
                assertThat(otherOrders).as("round %d", round).isEqualTo(1);
                assertThat(results.get(0)).isInstanceOf(PaymentNeedsReviewException.class);
                assertThat(stocks()).as("round %d: pA reserved by the late payment was given back", round).isEqualTo(but(before, O_B1, 0));
            }
        }
    }

    // ================================================================== 9. cancel and refund

    @Test
    void 성공_고객이_PAID_주문을_취소하면_옵션과_일반_재고가_정확히_한_번_돌아온다() {
        catalog();
        Map<String, Integer> before = baseline();
        String user = data.user();
        Placed p = paid(user, fullLines());
        assertThat(stocks()).isEqualTo(afterFull(before));

        assertThat(cancelService.cancelByCustomer(p.orderId(), user, "단순 변심")).isEqualTo("CANCELLED");

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(stocks()).isEqualTo(before);
        // a repeated cancel is idempotent and gives nothing back twice
        assertThat(cancelService.cancelByCustomer(p.orderId(), user, null)).isEqualTo("CANCELLED");
        assertThat(stocks()).isEqualTo(before);
        assertThat(gateway.cancelCalls).containsExactly(p.orderId());
    }

    @Test
    void 성공_세_번_동시에_취소해도_옵션_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 10; round++) {
            resetWithCatalog();
            Map<String, Integer> before = baseline();
            String user = data.user();
            Placed p = paid(user, fullLines());

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> cancelService.cancelByCustomer(p.orderId(), user, "동시 취소"));
            }
            List<Object> results = runTogether(tasks);

            assertNoThrowable(results, "round " + round);
            assertThat(results).as("round %d", round).containsOnly("CANCELLED");
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stocks()).as("round %d", round).isEqualTo(before);
        }
    }

    @Test
    void 실패_제공사가_취소를_거절하면_주문은_PAID로_돌아가고_재고는_그대로다() {
        catalog();
        Map<String, Integer> original = baseline();
        String user = data.user();
        Placed p = paid(user, fullLines());
        Snap afterPayment = snap();
        gateway.rejectCancel = true;

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), user, null)).isInstanceOf(CancelProviderUnavailableException.class);

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(snap()).isEqualTo(afterPayment);

        gateway.rejectCancel = false;
        assertThat(cancelService.cancelByCustomer(p.orderId(), user, null)).isEqualTo("CANCELLED");
        assertThat(stocks()).isEqualTo(original);
    }

    @Test
    void 성공_Transaction_Cancelled_웹훅이_PAID_주문에_오면_옵션과_일반_재고가_한_번만_돌아온다() throws Exception {
        catalog();
        Map<String, Integer> before = baseline();
        String user = data.user();
        Placed p = paid(user, fullLines());
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "CANCELLED", (long) p.total(), "KRW", "tx", "LIVE"));

        webhook("Transaction.Cancelled", p.orderId());

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(stocks()).isEqualTo(before);
        webhook("Transaction.Cancelled", p.orderId());
        assertThat(stocks()).as("a second webhook gives nothing back again").isEqualTo(before);
    }

    @Test
    void 성공_Transaction_Cancelled_웹훅이라도_제공사가_CANCELLED로_보지_않으면_재고를_바꾸지_않는다() throws Exception {
        catalog();
        String user = data.user();
        Placed p = paid(user, fullLines());
        Snap afterPayment = snap();

        webhook("Transaction.Cancelled", p.orderId()); // the provider still says PAID

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(snap()).isEqualTo(afterPayment);
    }

    @Test
    void 성공_배송_시작_뒤_관리자_환불은_옵션이든_일반_상품이든_재고를_돌려주지_않는다() {
        catalog();
        String user = data.user();
        Placed shipped = paid(user, fullLines());
        Placed delivered = paid(user, opt("pB", "pB-1", 1), line("pA", 1));
        assertThat(orderService.shipOrder(shipped.orderId())).isEqualTo("SHIPPED");
        assertThat(orderService.shipOrder(delivered.orderId())).isEqualTo("SHIPPED");
        assertThat(orderService.deliverOrder(delivered.orderId())).isEqualTo("DELIVERED");
        Snap beforeRefund = snap();

        assertThat(cancelService.refundByAdmin(shipped.orderId(), "상품 불량")).isEqualTo("REFUNDED");
        assertThat(snap()).as("SHIPPED -> REFUNDED").isEqualTo(beforeRefund);
        assertThat(cancelService.refundByAdmin(delivered.orderId(), "상품 불량")).isEqualTo("REFUNDED");
        assertThat(snap()).as("DELIVERED -> REFUNDED").isEqualTo(beforeRefund);

        // again: still nothing
        assertThat(cancelService.refundByAdmin(shipped.orderId(), "상품 불량")).isEqualTo("REFUNDED");
        assertThat(snap()).isEqualTo(beforeRefund);
    }

    @Test
    void 성공_배송_중인_주문의_취소_웹훅은_환불로만_처리하고_재고를_돌려주지_않는다() throws Exception {
        catalog();
        String user = data.user();
        Placed p = paid(user, fullLines());
        orderService.shipOrder(p.orderId());
        Snap beforeWebhook = snap();
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "CANCELLED", (long) p.total(), "KRW", "tx", "LIVE"));

        webhook("Transaction.Cancelled", p.orderId());

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(snap()).isEqualTo(beforeWebhook);
    }

    @Test
    void 성공_PAYMENT_REVIEW_주문을_취소_웹훅으로_닫아도_재고는_돌아오지_않는다() throws Exception {
        catalog();
        String user = data.user();
        Placed p = expired(user, fullLines());
        setStock(O_B2, 0); // the late payment cannot reserve pB-2
        providerPaid(p);
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), user)).isInstanceOf(PaymentNeedsReviewException.class);
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        Snap inReview = snap();
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "CANCELLED", (long) p.total(), "KRW", "tx", "LIVE"));

        webhook("Transaction.Cancelled", p.orderId());

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(snap()).as("the order never held stock in review").isEqualTo(inReview);
    }

    @Test
    void 실패_결제_전_주문의_취소_웹훅은_무시하고_예약한_재고는_그대로다() throws Exception {
        catalog();
        String user = data.user();
        Placed p = place(user, fullLines());
        Snap pending = snap();
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "CANCELLED", (long) p.total(), "KRW", "tx", "LIVE"));

        webhook("Transaction.Cancelled", p.orderId());

        assertThat(status(p.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(snap()).isEqualTo(pending);
    }

    // ================================================================== 10. reads

    @Test
    void 성공_내_주문_목록과_상세와_관리자_목록이_옵션_이름을_내려주고_일반_상품은_null이다() {
        catalog();
        optionProduct("pE", 500);
        option("e-1", "pE", null, "S", 500, 10, 0);
        String optionBuyer = data.user();
        String mixedBuyer = data.user();
        String legacyBuyer = data.user();
        String legacyLargestBuyer = data.user();
        Placed optionOrder = place(optionBuyer, opt("pB", "pB-2", 1));
        Placed mixedOrder = place(mixedBuyer, line("pA", 1), opt("pB", "pB-1", 1)); // the option line is the largest
        Placed legacyOrder = place(legacyBuyer, line("pC", 2));
        Placed legacyLargest = place(legacyLargestBuyer, line("pC", 3), opt("pE", "e-1", 1)); // 9,000 vs 500: legacy is the representative

        OrderDtos.OrderListResponse mine = OrderDtos.OrderListResponse.from(orderService.listMyOrders(optionBuyer, 10, null));
        OrderDtos.OrderListResponse mixed = OrderDtos.OrderListResponse.from(orderService.listMyOrders(mixedBuyer, 10, null));
        OrderDtos.OrderListResponse legacyList = OrderDtos.OrderListResponse.from(orderService.listMyOrders(legacyBuyer, 10, null));
        OrderDtos.OrderListResponse legacyLargestList = OrderDtos.OrderListResponse.from(orderService.listMyOrders(legacyLargestBuyer, 10, null));

        assertThat(mine.orders()).hasSize(1);
        assertThat(mine.orders().get(0).firstItemName()).isEqualTo("상품 pB");
        assertThat(mine.orders().get(0).firstItemOptionLabel()).isEqualTo("화이트 / L");
        assertThat(mixed.orders().get(0)).extracting(OrderDtos.OrderSummaryView::firstItemName, OrderDtos.OrderSummaryView::firstItemOptionLabel,
                OrderDtos.OrderSummaryView::itemCount).containsExactly("상품 pB", "화이트 / M", 2);
        assertThat(legacyList.orders().get(0).firstItemOptionLabel()).isNull();
        assertThat(legacyLargestList.orders().get(0)).extracting(OrderDtos.OrderSummaryView::firstItemName,
                OrderDtos.OrderSummaryView::firstItemOptionLabel).containsExactly("상품 pC", null);

        // detail: every line has its own label (null for the legacy line)
        OrderDtos.OrderView detail = OrderDtos.OrderView.from(orderService.getOrder(mixedOrder.orderId(), mixedBuyer).orElseThrow());
        assertThat(detail.items()).extracting(OrderDtos.OrderItemView::productName, OrderDtos.OrderItemView::optionLabel,
                OrderDtos.OrderItemView::price, OrderDtos.OrderItemView::quantity)
                .containsExactlyInAnyOrder(org.assertj.core.api.Assertions.tuple("상품 pA", null, 1000, 1),
                        org.assertj.core.api.Assertions.tuple("상품 pB", "화이트 / M", 20000, 1));
        assertThat(orderService.getOrder(mixedOrder.orderId(), optionBuyer)).as("someone else's order").isEmpty();

        // the admin list shows the same for every user's order
        Map<String, OrderDtos.AdminOrderView> admin = new HashMap<>();
        OrderDtos.AdminOrderListResponse.from(orderService.listAdminOrders(null, 50, null)).orders().forEach(o -> admin.put(o.id(), o));
        assertThat(admin).hasSize(4);
        assertThat(admin.get(optionOrder.orderId()).firstItemOptionLabel()).isEqualTo("화이트 / L");
        assertThat(admin.get(mixedOrder.orderId()).firstItemOptionLabel()).isEqualTo("화이트 / M");
        assertThat(admin.get(legacyOrder.orderId()).firstItemOptionLabel()).isNull();
        assertThat(admin.get(legacyLargest.orderId()).firstItemOptionLabel()).isNull();
        assertThat(admin.get(legacyLargest.orderId()).firstItemName()).isEqualTo("상품 pC");
        assertThat(admin.get(mixedOrder.orderId()).itemCount()).isEqualTo(2);
    }

    @Test
    void 성공_판매_집계는_옵션_주문도_상품_기준으로_세고_취소_대기_만료된_주문은_세지_않는다() {
        catalog();
        optionProduct("pE", 1000);
        option("e-1", "pE", null, "S", 1000, 50, 0);
        optionProduct("pF", 1000);
        option("f-1", "pF", null, "S", 1000, 50, 0);
        String user = data.user();
        paid(user, opt("pB", "pB-1", 2), opt("pB", "pB-2", 1)); // pB: 3 units over two options
        paid(user, line("pA", 2));                              // pA: 2 units
        place(user, line("pC", 9));                             // pending: not a sale
        expired(user, opt("pF", "f-1", 9));                     // expired: not a sale
        Placed cancelled = paid(user, opt("pE", "e-1", 9));     // cancelled below: not a sale
        assertThat(cancelService.cancelByCustomer(cancelled.orderId(), user, null)).isEqualTo("CANCELLED");
        Instant since = NOW.minus(Duration.ofDays(1));

        List<String> best = productRepository.findBestSoldSince(since, PageRequest.of(0, 20)).stream().map(Product::getId).toList();
        List<String> bySales = productService.listProducts(null, ProductSort.SALES, 0, 20).getContent().stream().map(Product::getId).toList();

        assertThat(best).containsExactly("pB", "pA"); // 3 units, then 2 units
        assertThat(bySales.subList(0, 2)).containsExactly("pB", "pA");
    }
}
