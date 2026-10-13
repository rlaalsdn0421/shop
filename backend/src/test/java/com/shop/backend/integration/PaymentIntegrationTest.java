package com.shop.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.application.service.PaymentGateway.Payment;
import com.shop.backend.application.service.PaymentService;
import com.shop.backend.application.service.PaymentTransactions;
import com.shop.backend.application.service.WebhookSignatureVerifier;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentAmountMismatchException;
import com.shop.backend.domain.error.PaymentChannelNotAllowedException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentNotConfirmedException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment flow on a real Postgres (V15 constraints/index, row locks, SKIP LOCKED, stock bookkeeping). Only the
 * provider is faked: {@link StubGateway} answers per payment id. The clock is the fixed one of IntegrationTestConfig,
 * so "overdue" is made by moving orders.expires_at into the past with SQL.
 */
@TestPropertySource(properties = {
        "portone.store-id=" + PaymentIntegrationTest.STORE_ID,
        "portone.webhook-secret=" + PaymentIntegrationTest.WEBHOOK_SECRET,
        "portone.allow-test-channel=false"})
class PaymentIntegrationTest extends PostgresIntegrationTest {

    static final String STORE_ID = "store-test-0001";
    // Made-up key, base64 of "integration-test-webhook-key-0123" (not a real secret)
    static final String WEBHOOK_SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC13ZWJob29rLWtleS0wMTIz";
    private static final byte[] WEBHOOK_KEY = "integration-test-webhook-key-0123".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = IntegrationTestConfig.FIXED_NOW;

    /** Fake provider: the answer is controlled per payment id; counts how often it was asked. */
    public static class StubGateway implements PaymentGateway {
        final Map<String, Payment> payments = new ConcurrentHashMap<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean unavailable;
        volatile boolean configured = true;

        void reset() {
            payments.clear();
            calls.set(0);
            unavailable = false;
            configured = true;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Optional<Payment> find(String paymentId) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new PaymentProviderUnavailableException();
            }
            return Optional.ofNullable(payments.get(paymentId));
        }

        @Override
        public void cancel(String paymentId, String reason, Requester requester) {
            // not used by the payment flow tests
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PaymentService.class, PaymentTransactions.class, WebhookSignatureVerifier.class})
    static class PaymentTestConfig {
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
    private PaymentService paymentService;
    @Autowired
    private PaymentTransactions transactions;
    @Autowired
    private StubGateway gateway;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private WebhookSignatureVerifier verifier;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    // ------------------------------------------------------------------ helpers

    private record Placed(String userId, String orderId, int total, String key, OrderLine[] lines) {
    }

    private void product(String id, int stock) {
        data.product(id, null, 1000, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    private int stock(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private Map<String, Object> row(String orderId) {
        return jdbc.queryForMap("SELECT status, expires_at, paid_at, payment_transaction_id FROM orders WHERE id = ?", orderId);
    }

    private Instant instantOf(String column, String orderId) {
        Timestamp ts = jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", Timestamp.class, orderId);
        return ts == null ? null : ts.toInstant();
    }

    private Placed place(String userId, OrderLine... lines) {
        String key = "key-" + UUID.randomUUID();
        Order order = orderService.createOrder(userId, key, "구매자", "010-0000-0000", "서울", List.of(lines));
        return new Placed(userId, order.getId(), order.getTotalAmount(), key, lines);
    }

    private Order replay(Placed p) {
        return orderService.createOrder(p.userId(), p.key(), "구매자", "010-0000-0000", "서울", List.of(p.lines()));
    }

    private static OrderLine line(String productId, int quantity) {
        return new OrderLine(productId, quantity);
    }

    private void overdue(String orderId) {
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), orderId);
    }

    private void expiresAt(String orderId, Instant at) {
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", OffsetDateTime.ofInstant(at, ZoneOffset.UTC), orderId);
    }

    private static Payment live(String orderId, int total) {
        return new Payment(orderId, STORE_ID, "PAID", (long) total, "KRW", "tx-" + orderId.substring(0, 8), "LIVE");
    }

    private void providerPaid(Placed p) {
        gateway.payments.put(p.orderId(), live(p.orderId(), p.total()));
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static String paidBody(String paymentId) {
        return "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"" + STORE_ID + "\",\"transactionId\":\"tx-1\"}}";
    }

    private void webhook(String body) throws Exception {
        String id = "msg_" + UUID.randomUUID();
        String ts = String.valueOf(NOW.getEpochSecond());
        paymentService.handleWebhook(id, ts, sign(id, ts, body), body);
    }

    private void webhookPaid(String paymentId) throws Exception {
        webhook(paidBody(paymentId));
    }

    /** Asserts the call fails with {@code type} and leaves the order row and all given stocks exactly as they were. */
    private void assertRejectedUntouched(Placed p, Class<? extends Throwable> type, Map<String, Integer> stocks) {
        Map<String, Object> before = row(p.orderId());
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), p.userId())).isInstanceOf(type);
        assertThat(row(p.orderId())).isEqualTo(before);
        stocks.forEach((id, expected) -> assertThat(stock(id)).as("stock of %s", id).isEqualTo(expected));
    }

    private void assertRejectedUntouched(Placed p, Class<? extends Throwable> type) {
        assertRejectedUntouched(p, type, Map.of("p1", stock("p1")));
    }

    private interface Body {
        void run() throws Exception;
    }

    private static Throwable failureOf(Body body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

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
                results.add(future.get(40, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertNoThrowable(List<Object> results, String context) {
        assertThat(results.stream().filter(r -> r instanceof Throwable).toList()).as(context).isEmpty();
    }

    private void assertStockMatchesStatus(String orderId, String productId, int initial, int quantity, String context) {
        String status = status(orderId);
        int stock = stock(productId);
        assertThat(stock).as("%s: stock never negative", context).isGreaterThanOrEqualTo(0);
        switch (status) {
            case "PAID", "PENDING_PAYMENT" -> assertThat(stock).as("%s: %s => stock reserved", context, status).isEqualTo(initial - quantity);
            case "EXPIRED" -> assertThat(stock).as("%s: EXPIRED => stock given back", context).isEqualTo(initial);
            default -> throw new AssertionError(context + ": unexpected status " + status);
        }
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

    private void insertOrderWithStatus(String status) {
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                        + "VALUES (?, 'n', 'p', 'a', 1, ?, ?)",
                UUID.randomUUID().toString(), status, Timestamp.valueOf(LocalDateTime.now()));
    }

    // ------------------------------------------------------------------ 1. V15

    @Test
    void 성공_V15_새_컬럼_세_개가_있고_타입과_길이가_맞다() {
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable "
                + "FROM information_schema.columns WHERE table_name = 'orders' "
                + "AND column_name IN ('expires_at', 'paid_at', 'payment_transaction_id') ORDER BY column_name");

        assertThat(columns).hasSize(3);
        assertThat(columns).allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));
        assertThat(columns.get(0)).containsEntry("column_name", "expires_at").containsEntry("data_type", "timestamp with time zone");
        assertThat(columns.get(1)).containsEntry("column_name", "paid_at").containsEntry("data_type", "timestamp with time zone");
        assertThat(columns.get(2)).containsEntry("column_name", "payment_transaction_id")
                .containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 100);
    }

    @Test
    void 성공_orders_status_valid는_아홉_가지_상태를_받는다() {
        for (String status : List.of("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_REVIEW", "CANCELING", "CANCELLED",
                "SHIPPED", "DELIVERED", "REFUNDED")) {
            assertThatCode(() -> insertOrderWithStatus(status)).as(status).doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(9);
    }

    @Test
    void 실패_orders_status_valid는_알_수_없는_상태와_대소문자가_다른_값을_거부한다() {
        for (String status : List.of("UNKNOWN", "paid", "", "cancelled", "PAID ")) {
            assertThatThrownBy(() -> insertOrderWithStatus(status)).as("[%s]", status)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("orders_status_valid");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void 실패_기존_주문을_알_수_없는_상태로_바꾸는_UPDATE도_거부한다() {
        insertOrderWithStatus("PAID");

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'UNKNOWN'"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_status_valid");
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("PAID");
    }

    @Test
    void 성공_부분_인덱스가_PENDING_PAYMENT_행의_expires_at에만_걸려_있다() {
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE tablename = 'orders' AND indexname = 'idx_orders_pending_expires'", String.class);

        assertThat(def).contains("(expires_at)").contains("WHERE").contains("PENDING_PAYMENT");
    }

    @Test
    void 성공_만료_후보_조회는_부분_인덱스를_쓸_수_있고_PAID_조회는_쓰지_못한다() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertOrderWithStatus("PENDING_PAYMENT");
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            try {
                st.execute("SET enable_seqscan = off");
                String pending = explain(st, "SELECT id FROM orders WHERE status = 'PENDING_PAYMENT' AND expires_at < now() ORDER BY expires_at");
                String paid = explain(st, "SELECT id FROM orders WHERE status = 'PAID' AND expires_at < now() ORDER BY expires_at");

                assertThat(pending).contains("idx_orders_pending_expires");
                assertThat(paid).doesNotContain("idx_orders_pending_expires");
            } finally {
                st.execute("RESET enable_seqscan"); // the connection goes back to the pool
            }
        }
    }

    private static String explain(Statement st, String sql) throws Exception {
        StringBuilder plan = new StringBuilder();
        try (var rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    // ------------------------------------------------------------------ 2. happy path

    @Test
    void 성공_주문하면_결제_대기_상태로_20분_뒤_만료되고_재고가_차감된다() {
        product("p1", 10);

        Placed p = place(data.user(), line("p1", 3));

        assertThat(status(p.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(instantOf("expires_at", p.orderId())).isEqualTo(NOW.plusSeconds(20 * 60));
        assertThat(instantOf("paid_at", p.orderId())).isNull();
        assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId())).isNull();
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(p.total()).isEqualTo(3 * 1000 + 3500);
    }

    @Test
    void 성공_결제를_확인하면_PAID가_되고_시각과_거래_id가_기록되며_재고는_그대로다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p); // total includes the 3,500 shipping fee

        String result = paymentService.complete(p.orderId(), p.userId());

        assertThat(result).isEqualTo("PAID");
        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId()))
                .isEqualTo("tx-" + p.orderId().substring(0, 8));
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_이미_PAID인_주문에_다시_확인해도_아무것도_바뀌지_않고_포트원도_부르지_않는다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);
        paymentService.complete(p.orderId(), p.userId());
        Map<String, Object> afterFirst = row(p.orderId());
        int callsAfterFirst = gateway.calls.get();
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW", "tx-other", "LIVE"));

        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("PAID");

        assertThat(row(p.orderId())).isEqualTo(afterFirst);
        assertThat(gateway.calls.get()).isEqualTo(callsAfterFirst);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_DB_전이_자체도_멱등이다_이미_PAID인_행에_applyPaid를_또_불러도_거래_id가_그대로다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        assertThat(transactions.applyPaid(p.orderId(), "tx-first")).isEqualTo("PAID");

        assertThat(transactions.applyPaid(p.orderId(), "tx-second")).isEqualTo("PAID");

        assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId())).isEqualTo("tx-first");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_서명된_웹훅으로도_같은_결과로_PAID가_된다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);

        webhookPaid(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId()))
                .isEqualTo("tx-" + p.orderId().substring(0, 8));
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_우리_주문이_아닌_paymentId의_웹훅은_무시하고_포트원도_부르지_않는다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        assertThatCode(() -> webhookPaid(UUID.randomUUID().toString())).doesNotThrowAnyException();

        assertThat(gateway.calls.get()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_서명이_틀린_웹훅은_거부하고_주문은_그대로다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);
        Map<String, Object> before = row(p.orderId());
        String body = paidBody(p.orderId());
        String ts = String.valueOf(NOW.getEpochSecond());

        assertThatThrownBy(() -> paymentService.handleWebhook("msg_1", ts, sign("msg_other", ts, body), body))
                .isInstanceOf(ValidationException.class);

        assertThat(gateway.calls.get()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);
    }

    @Test
    void 성공_Transaction_Paid가_아닌_웹훅_타입은_서명이_맞아도_무시한다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);
        Map<String, Object> before = row(p.orderId());

        webhook(paidBody(p.orderId()).replace("Transaction.Paid", "Transaction.VirtualAccountIssued"));

        assertThat(gateway.calls.get()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 3. rejections leave the DB untouched

    private Placed pendingP1(int quantity) {
        product("p1", 10);
        return place(data.user(), line("p1", quantity));
    }

    @Test
    void 실패_금액이_다르면_PAYMENT_AMOUNT_MISMATCH이고_DB는_그대로다() {
        Placed p = pendingP1(2); // total 5,500 (incl. shipping)
        for (long wrong : new long[]{p.total() - 1, p.total() + 1, 2000L /* items only, shipping forgotten */, 0L}) {
            gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", wrong, "KRW", "tx", "LIVE"));
            assertRejectedUntouched(p, PaymentAmountMismatchException.class);
        }
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", null, "KRW", "tx", "LIVE"));
        assertRejectedUntouched(p, PaymentAmountMismatchException.class);
    }

    @Test
    void 실패_통화가_KRW가_아니거나_결제_id가_주문과_다르면_거부한다() {
        Placed p = pendingP1(2);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "USD", "tx", "LIVE"));
        assertRejectedUntouched(p, PaymentAmountMismatchException.class);

        gateway.payments.put(p.orderId(), new Payment("another-order-id", STORE_ID, "PAID", (long) p.total(), "KRW", "tx", "LIVE"));
        assertRejectedUntouched(p, PaymentAmountMismatchException.class);
    }

    @Test
    void 실패_storeId가_설정과_다르면_거부하고_DB는_그대로다() {
        Placed p = pendingP1(2);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), "someone-elses-store", "PAID", (long) p.total(), "KRW", "tx", "LIVE"));
        assertRejectedUntouched(p, PaymentAmountMismatchException.class);

        gateway.payments.put(p.orderId(), new Payment(p.orderId(), null, "PAID", (long) p.total(), "KRW", "tx", "LIVE"));
        assertRejectedUntouched(p, PaymentAmountMismatchException.class);
    }

    @Test
    void 실패_TEST_채널이거나_채널_정보가_없으면_PAYMENT_CHANNEL_NOT_ALLOWED이고_DB는_그대로다() {
        Placed p = pendingP1(2);
        for (String channel : new String[]{"TEST", null, "UNKNOWN", "live"}) {
            gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW", "tx", channel));
            assertRejectedUntouched(p, PaymentChannelNotAllowedException.class);
        }
    }

    @Test
    void 성공_allow_test_channel을_켜면_TEST_채널도_PAID가_되지만_채널_정보가_없는_결제는_여전히_거부한다() {
        PaymentService allowing = new PaymentService(orderRepository, gateway, transactions, verifier, objectMapper, clock, STORE_ID, true);
        Placed p = pendingP1(2);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW", "tx", null));
        Map<String, Object> before = row(p.orderId());
        assertThatThrownBy(() -> allowing.complete(p.orderId(), p.userId())).isInstanceOf(PaymentChannelNotAllowedException.class);
        assertThat(row(p.orderId())).isEqualTo(before);

        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW", "tx-test", "TEST"));
        assertThat(allowing.complete(p.orderId(), p.userId())).isEqualTo("PAID");
        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(8);
    }

    @Test
    void 실패_포트원_상태가_PAID가_아니거나_결제가_아직_없으면_PAYMENT_NOT_CONFIRMED이고_DB는_그대로다() {
        Placed p = pendingP1(2);
        assertRejectedUntouched(p, PaymentNotConfirmedException.class); // provider has no such payment yet

        for (String status : new String[]{"READY", "PENDING", "FAILED", "CANCELLED", "PARTIAL_CANCELLED", "VIRTUAL_ACCOUNT_ISSUED", "paid"}) {
            gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, status, (long) p.total(), "KRW", "tx", "LIVE"));
            assertRejectedUntouched(p, PaymentNotConfirmedException.class);
        }
    }

    @Test
    void 실패_다른_사용자의_주문이나_없는_주문은_404_동작이고_포트원도_부르지_않으며_DB는_그대로다() {
        Placed p = pendingP1(2);
        providerPaid(p);
        Map<String, Object> before = row(p.orderId());
        String stranger = data.user();

        assertThatThrownBy(() -> paymentService.complete(p.orderId(), stranger)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> paymentService.complete(UUID.randomUUID().toString(), p.userId())).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), "")).isInstanceOf(OrderNotFoundException.class);

        assertThat(gateway.calls.get()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(8);
        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("PAID"); // the owner still can
    }

    @Test
    void 실패_포트원이_응답하지_않으면_PAYMENT_PROVIDER_UNAVAILABLE이고_DB는_그대로이며_복구되면_성공한다() {
        Placed p = pendingP1(2);
        providerPaid(p);
        gateway.unavailable = true;
        assertRejectedUntouched(p, PaymentProviderUnavailableException.class);

        gateway.unavailable = false;
        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("PAID");
    }

    @Test
    void 실패_결제_설정이_비어_있으면_PAYMENT_NOT_CONFIGURED이고_DB는_그대로다() {
        Placed p = pendingP1(2);
        providerPaid(p);
        gateway.configured = false;
        assertRejectedUntouched(p, PaymentNotConfiguredException.class);

        PaymentService noStore = new PaymentService(orderRepository, gateway, transactions, verifier, objectMapper, clock, "", false);
        gateway.configured = true;
        Map<String, Object> before = row(p.orderId());
        assertThatThrownBy(() -> noStore.complete(p.orderId(), p.userId())).isInstanceOf(PaymentNotConfiguredException.class);
        assertThat(row(p.orderId())).isEqualTo(before);
    }

    @Test
    void 실패_웹훅에서_금액이_다르면_예외_없이_무시하고_주문은_PENDING_PAYMENT로_남는다() throws Exception {
        Placed p = pendingP1(2);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", 1L, "KRW", "tx", "LIVE"));
        Map<String, Object> before = row(p.orderId());

        assertThatCode(() -> webhookPaid(p.orderId())).doesNotThrowAnyException();

        assertThat(row(p.orderId())).isEqualTo(before);
    }

    @Test
    void 실패_웹훅에서_포트원이_장애면_예외로_전달되어_포트원이_재시도할_수_있고_DB는_그대로다() {
        Placed p = pendingP1(2);
        providerPaid(p);
        gateway.unavailable = true;
        Map<String, Object> before = row(p.orderId());

        assertThatThrownBy(() -> webhookPaid(p.orderId())).isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 4. expiry

    @Test
    void 성공_기한이_지난_대기_주문은_EXPIRED가_되고_모든_품목의_재고가_돌아온다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = place(data.user(), line("p1", 3), line("p2", 4));
        overdue(p.orderId());
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(stock("p2")).isEqualTo(6);

        assertThat(paymentService.expirePending()).isEqualTo(1);

        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stock("p1")).isEqualTo(10);
        assertThat(stock("p2")).isEqualTo(10);
    }

    @Test
    void 성공_정리를_두_번_돌려도_재고는_한_번만_돌아온다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        overdue(p.orderId());

        assertThat(paymentService.expirePending()).isEqualTo(1);
        assertThat(paymentService.expirePending()).isZero();
        assertThat(transactions.expireOne(p.orderId())).isFalse();

        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_여러_스레드가_동시에_정리해도_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 10; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 10);
            Placed p = place(data.user(), line("p1", 3));
            overdue(p.orderId());

            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> paymentService.expirePending());
            }
            List<Object> results = runTogether(tasks);

            assertNoThrowable(results, "round " + round);
            assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).as("round %d: orders expired in total", round).isEqualTo(1);
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("EXPIRED");
            assertThat(stock("p1")).as("round %d: stock", round).isEqualTo(10);
        }
    }

    @Test
    void 성공_PAID_주문은_기한이_지나도_정리되지_않는다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);
        paymentService.complete(p.orderId(), p.userId());
        overdue(p.orderId());

        assertThat(paymentService.expirePending()).isZero();
        assertThat(transactions.expireOne(p.orderId())).isFalse();

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_아직_기한이_지나지_않은_주문은_건드리지_않고_기한_정각은_아직_유효하며_1ms_지나면_정리된다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        assertThat(paymentService.expirePending()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);

        expiresAt(p.orderId(), NOW); // exactly now: not yet overdue
        assertThat(paymentService.expirePending()).isZero();
        assertThat(transactions.expireOne(p.orderId())).isFalse();
        assertThat(status(p.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(stock("p1")).isEqualTo(7);

        expiresAt(p.orderId(), NOW.minusMillis(1));
        assertThat(paymentService.expirePending()).isEqualTo(1);
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_한_번에_100건까지만_정리하고_가장_오래된_기한부터이며_나머지는_다음_실행에서_정리된다() {
        product("p1", 1000);
        String user = data.user();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            Placed p = place(user, line("p1", 1));
            ids.add(p.orderId());
            expiresAt(p.orderId(), NOW.minusSeconds(3600).plusSeconds(i)); // i = 0 is the oldest deadline
        }
        assertThat(stock("p1")).isEqualTo(895);

        assertThat(paymentService.expirePending()).isEqualTo(100);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'PENDING_PAYMENT'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE status = 'EXPIRED'", Integer.class)).isEqualTo(100);
        for (int i = 100; i < 105; i++) {
            assertThat(status(ids.get(i))).as("newest deadlines wait for the next run").isEqualTo("PENDING_PAYMENT");
        }
        assertThat(stock("p1")).isEqualTo(995);

        assertThat(paymentService.expirePending()).isEqualTo(5);
        assertThat(paymentService.expirePending()).isZero();
        assertThat(stock("p1")).isEqualTo(1000);
    }

    // ------------------------------------------------------------------ 5. late payment on an EXPIRED order

    @Test
    void 성공_정리된_주문에_결제가_확인되면_재고가_있으면_PAID이고_재고는_정확히_한_번만_다시_차감된다() throws Exception {
        product("p1", 10);
        product("p2", 10);
        Placed p = place(data.user(), line("p1", 3), line("p2", 4));
        overdue(p.orderId());
        paymentService.expirePending();
        assertThat(stock("p1")).isEqualTo(10);
        providerPaid(p);

        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("PAID");

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(stock("p2")).isEqualTo(6);

        // repeated confirmations (browser again, webhook) and the sweeper change nothing
        paymentService.complete(p.orderId(), p.userId());
        webhookPaid(p.orderId());
        transactions.applyPaid(p.orderId(), "tx-again");
        paymentService.expirePending();
        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(stock("p2")).isEqualTo(6);
    }

    @Test
    void 성공_정리된_주문의_재고가_딱_필요한_만큼이면_PAID가_되고_재고는_0이_된다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        overdue(p.orderId());
        paymentService.expirePending();
        jdbc.update("UPDATE products SET stock = 3 WHERE id = 'p1'"); // other buyers took the rest
        providerPaid(p);

        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("PAID");

        assertThat(stock("p1")).isZero();
    }

    /** Three lines; {@code shortProduct} has too little stock when the late payment arrives. */
    private void assertLatePaymentGoesToReview(String shortProduct) throws Exception {
        product("pA", 10);
        product("pB", 10);
        product("pC", 10);
        Placed p = place(data.user(), line("pA", 2), line("pB", 3), line("pC", 1));
        overdue(p.orderId());
        paymentService.expirePending();
        jdbc.update("UPDATE products SET stock = 5 WHERE id IN ('pA', 'pB')");
        jdbc.update("UPDATE products SET stock = 0 WHERE id = ?", shortProduct);
        Map<String, Integer> afterExpiry = Map.of("pA", stock("pA"), "pB", stock("pB"), "pC", stock("pC"));
        providerPaid(p);

        assertThatThrownBy(() -> paymentService.complete(p.orderId(), p.userId()))
                .isInstanceOfSatisfying(PaymentNeedsReviewException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_NEEDS_REVIEW"));

        // PAYMENT_REVIEW is committed (not rolled back with the exception); nothing stays reserved
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(instantOf("paid_at", p.orderId())).isNull();
        assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId())).isNull();
        afterExpiry.forEach((id, expected) -> assertThat(stock(id)).as("stock of %s", id).isEqualTo(expected));

        // asking again, the webhook and the sweeper leave it alone
        assertRejectedUntouched(p, PaymentNeedsReviewException.class, afterExpiry);
        assertThatCode(() -> webhookPaid(p.orderId())).doesNotThrowAnyException();
        assertThat(paymentService.expirePending()).isZero();
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        afterExpiry.forEach((id, expected) -> assertThat(stock(id)).as("stock of %s", id).isEqualTo(expected));
    }

    @Test
    void 실패_정리된_주문의_마지막_품목_재고가_모자라면_PAYMENT_REVIEW이고_앞서_잡은_재고는_모두_되돌려진다() throws Exception {
        assertLatePaymentGoesToReview("pC");
    }

    @Test
    void 실패_정리된_주문의_가운데_품목_재고가_모자라면_PAYMENT_REVIEW이고_앞서_잡은_재고는_모두_되돌려진다() throws Exception {
        assertLatePaymentGoesToReview("pB");
    }

    @Test
    void 실패_정리된_주문의_첫_품목_재고가_모자라면_PAYMENT_REVIEW이고_재고는_그대로다() throws Exception {
        assertLatePaymentGoesToReview("pA");
    }

    // ------------------------------------------------------------------ 6. races

    @Test
    void 성공_같은_기한_지난_주문에서_결제_확인과_정리가_동시에_와도_항상_PAID이고_재고는_한_번만_잡힌다() throws Exception {
        for (int round = 0; round < 30; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            product("p1", 10);
            Placed p = place(data.user(), line("p1", 3));
            overdue(p.orderId());
            providerPaid(p);

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> paymentService.expirePending()));

            assertNoThrowable(results, "round " + round);
            assertThat(status(p.orderId())).as("round %d: a confirmed payment always wins", round).isEqualTo("PAID");
            assertStockMatchesStatus(p.orderId(), "p1", 10, 3, "round " + round);
            assertThat(stock("p1")).as("round %d", round).isEqualTo(7);
        }
    }

    @Test
    void 성공_결제_확인과_정리를_여러_스레드가_섞어서_동시에_해도_상태와_재고가_어긋나지_않는다() throws Exception {
        for (int round = 0; round < 20; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            product("p1", 10);
            Placed p = place(data.user(), line("p1", 3));
            overdue(p.orderId());
            providerPaid(p);

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> paymentService.expirePending(),
                    () -> {
                        webhookPaid(p.orderId());
                        return null;
                    },
                    () -> paymentService.expirePending()));

            assertNoThrowable(results, "round " + round);
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("PAID");
            assertThat(stock("p1")).as("round %d", round).isEqualTo(7);
        }
    }

    @Test
    void 성공_브라우저_확인과_웹훅이_동시에_와도_한_번만_반영된다() throws Exception {
        for (int round = 0; round < 30; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            product("p1", 10);
            Placed p = place(data.user(), line("p1", 3));
            providerPaid(p);

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> {
                        webhookPaid(p.orderId());
                        return null;
                    }));

            assertNoThrowable(results, "round " + round);
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("PAID");
            assertThat(stock("p1")).as("round %d", round).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT payment_transaction_id FROM orders WHERE id = ?", String.class, p.orderId()))
                    .as("round %d", round).isEqualTo("tx-" + p.orderId().substring(0, 8));
        }
    }

    @Test
    void 성공_정리된_주문에_브라우저_확인과_웹훅이_동시에_와도_재고는_정확히_한_번만_다시_차감된다() throws Exception {
        for (int round = 0; round < 20; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            product("p1", 10);
            product("p2", 10);
            Placed p = place(data.user(), line("p1", 3), line("p2", 2));
            overdue(p.orderId());
            paymentService.expirePending();
            providerPaid(p);

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> {
                        webhookPaid(p.orderId());
                        return null;
                    }));

            assertNoThrowable(results, "round " + round);
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("PAID");
            assertThat(stock("p1")).as("round %d: p1", round).isEqualTo(7);
            assertThat(stock("p2")).as("round %d: p2", round).isEqualTo(8);
        }
    }

    @Test
    void 성공_정리된_주문의_늦은_결제가_마지막_재고를_두고_다른_주문과_경쟁해도_초과_판매되지_않는다() throws Exception {
        for (int round = 0; round < 15; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            product("p1", 1);
            String buyer = data.user();
            Placed expired = place(buyer, line("p1", 1));
            overdue(expired.orderId());
            paymentService.expirePending(); // stock 1 again
            providerPaid(expired);
            String other = data.user();

            List<Object> results = runTogether(List.of(
                    () -> paymentService.complete(expired.orderId(), buyer),
                    () -> place(other, line("p1", 1))));

            // whoever gets the last unit: never both, never negative
            assertThat(stock("p1")).as("round %d", round).isZero();
            String latePaymentStatus = status(expired.orderId());
            Integer otherOrders = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, other);
            if ("PAID".equals(latePaymentStatus)) {
                assertThat(otherOrders).as("round %d: the other order must have failed", round).isZero();
                assertThat(results.get(1)).isInstanceOf(Throwable.class);
            } else {
                assertThat(latePaymentStatus).as("round %d", round).isEqualTo("PAYMENT_REVIEW");
                assertThat(otherOrders).as("round %d", round).isEqualTo(1);
                assertThat(results.get(0)).isInstanceOf(PaymentNeedsReviewException.class);
            }
        }
    }

    // ------------------------------------------------------------------ 7. SKIP LOCKED

    @Test
    void 성공_결제_확정이_행을_잠그고_있으면_정리는_기다리지_않고_false이며_아무것도_바꾸지_않는다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        overdue(p.orderId());
        Map<String, Object> before = row(p.orderId());

        try (RowHolder holder = new RowHolder(p.orderId())) {
            long start = System.nanoTime();
            boolean expired = transactions.expireOne(p.orderId());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(expired).isFalse();
            assertThat(elapsedMillis).as("skipped, not waited for the 3 s lock_timeout").isLessThan(1_500L);

            start = System.nanoTime();
            assertThat(paymentService.expirePending()).isZero();
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_500L);
        }

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);

        assertThat(paymentService.expirePending()).as("lock released: the next run expires it").isEqualTo(1);
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_잠긴_주문이_섞여_있어도_나머지_기한_지난_주문은_정리된다() throws Exception {
        product("p1", 10);
        String user = data.user();
        Placed locked = place(user, line("p1", 2));
        Placed free = place(user, line("p1", 3));
        overdue(locked.orderId());
        overdue(free.orderId());

        try (RowHolder holder = new RowHolder(locked.orderId())) {
            assertThat(paymentService.expirePending()).isEqualTo(1);
        }

        assertThat(status(locked.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(status(free.orderId())).isEqualTo("EXPIRED");
        assertThat(stock("p1")).isEqualTo(10 - 2);
    }

    // ------------------------------------------------------------------ 8. replay of an overdue pending order

    @Test
    void 성공_기한이_지난_대기_주문을_같은_키로_다시_주문하면_EXPIRED로_돌려주고_재고를_한_번만_되돌린다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        overdue(p.orderId());

        Order replayed = replay(p);

        assertThat(replayed.getId()).isEqualTo(p.orderId());
        assertThat(replayed.getStatus()).isEqualTo("EXPIRED");
        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stock("p1")).isEqualTo(10);

        assertThat(replay(p).getStatus()).isEqualTo("EXPIRED");
        assertThat(paymentService.expirePending()).isZero();
        assertThat(stock("p1")).as("not restored twice").isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void 성공_기한이_지나지_않은_대기_주문의_재생은_아무것도_바꾸지_않는다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        Order replayed = replay(p);

        assertThat(replayed.getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);

        expiresAt(p.orderId(), NOW); // exactly now is not overdue either
        assertThat(replay(p).getStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_기한이_지났어도_이미_PAID인_주문의_재생은_PAID로_돌려주고_재고를_되돌리지_않는다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerPaid(p);
        paymentService.complete(p.orderId(), p.userId());
        overdue(p.orderId());

        assertThat(replay(p).getStatus()).isEqualTo("PAID");

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_재생이_행_잠금을_기다리는_동안_결제가_확정되면_만료시키지_않고_PAID로_돌려준다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            product("p1", 10);
            Placed p = place(data.user(), line("p1", 3));
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
                    .as("round %d: the payment data survived", round).isEqualTo("tx-holder");
            assertThat(stock("p1")).as("round %d: stock must not be restored for a paid order", round).isEqualTo(7);
        }
    }

    @Test
    void 성공_재생이_기다리던_잠금이_결제_없이_풀리면_그때_만료시키고_재고를_되돌린다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
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
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_만료된_주문을_재생한_뒤_같은_장바구니로_새_키로_다시_주문할_수_있다() {
        product("p1", 3);
        String user = data.user();
        Placed first = place(user, line("p1", 3)); // takes the last units
        overdue(first.orderId());
        assertThat(stock("p1")).isZero();

        assertThat(replay(first).getStatus()).isEqualTo("EXPIRED");
        Placed second = place(user, line("p1", 3));

        assertThat(second.orderId()).isNotEqualTo(first.orderId());
        assertThat(status(second.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(stock("p1")).isZero();
    }
}
