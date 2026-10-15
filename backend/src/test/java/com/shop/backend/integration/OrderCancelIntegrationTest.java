package com.shop.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.OrderService.OrderPage;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.application.service.PaymentGateway.Payment;
import com.shop.backend.application.service.PaymentGateway.Requester;
import com.shop.backend.application.service.PaymentService;
import com.shop.backend.application.service.PaymentTransactions;
import com.shop.backend.application.service.WebhookSignatureVerifier;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order cancel / refund / shipping on a real Postgres (V16 constraints, row locks, keyset admin list, sales aggregates,
 * real-thread races). Only the provider is faked: {@link StubGateway} answers payment lookups and cancel() per payment
 * id and records every cancel call. The clock is the fixed one of IntegrationTestConfig.
 */
@TestPropertySource(properties = {
        "portone.store-id=" + OrderCancelIntegrationTest.STORE_ID,
        "portone.webhook-secret=" + OrderCancelIntegrationTest.WEBHOOK_SECRET,
        "portone.allow-test-channel=false"})
class OrderCancelIntegrationTest extends PostgresIntegrationTest {

    static final String STORE_ID = "store-test-0001";
    // Made-up key, base64 of "integration-test-webhook-key-0123" (not a real secret)
    static final String WEBHOOK_SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC13ZWJob29rLWtleS0wMTIz";
    private static final byte[] WEBHOOK_KEY = "integration-test-webhook-key-0123".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = IntegrationTestConfig.FIXED_NOW;
    private static final String DEFAULT_REASON = "고객 요청 취소";

    /** How the fake provider answers cancel() for one payment id (default OK). */
    enum CancelMode {
        /** Cancelled, or "was already cancelled": returns normally. */
        OK,
        /** The provider answered with an error: nothing was cancelled (outcomeUnknown = false). */
        REJECTED,
        /** Timeout and the cancel did NOT happen at the provider (outcomeUnknown = true). */
        TIMEOUT_NOT_APPLIED,
        /** Timeout but the provider did cancel the payment (outcomeUnknown = true). */
        TIMEOUT_APPLIED
    }

    record CancelCall(String paymentId, String reason, Requester requester) {
    }

    /** Fake provider; thread safe because the race tests call it from several threads. */
    public static class StubGateway implements PaymentGateway {
        final Map<String, Payment> payments = new ConcurrentHashMap<>();
        final Map<String, CancelMode> cancelModes = new ConcurrentHashMap<>();
        final List<CancelCall> cancelCalls = new CopyOnWriteArrayList<>();
        final AtomicInteger findCalls = new AtomicInteger();
        volatile boolean unavailable;
        volatile boolean configured = true;

        void reset() {
            payments.clear();
            cancelModes.clear();
            cancelCalls.clear();
            findCalls.set(0);
            unavailable = false;
            configured = true;
        }

        @Override
        public boolean isConfigured() {
            return configured;
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
            cancelCalls.add(new CancelCall(paymentId, reason, requester));
            switch (cancelModes.getOrDefault(paymentId, CancelMode.OK)) {
                case OK -> markCancelled(paymentId);
                case REJECTED -> throw new CancelProviderUnavailableException(false);
                case TIMEOUT_NOT_APPLIED -> throw new CancelProviderUnavailableException(true);
                case TIMEOUT_APPLIED -> {
                    markCancelled(paymentId);
                    throw new CancelProviderUnavailableException(true);
                }
            }
        }

        void markCancelled(String paymentId) {
            payments.computeIfPresent(paymentId, (id, p) ->
                    new Payment(p.id(), p.storeId(), "CANCELLED", p.total(), p.currency(), p.transactionId(), p.channelType()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PaymentService.class, PaymentTransactions.class, WebhookSignatureVerifier.class, OrderCancelService.class})
    static class CancelTestConfig {
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
    private StubGateway gateway;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    // ------------------------------------------------------------------ helpers

    private record Placed(String userId, String orderId, int total) {
    }

    private static OrderLine line(String productId, int quantity) {
        return new OrderLine(productId, quantity);
    }

    private void product(String id, int stock) {
        data.product(id, null, 1000, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    private int stock(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private Map<String, Integer> stocks(String... ids) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : ids) {
            result.put(id, stock(id));
        }
        return result;
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    /** Every column a transition can touch. */
    private Map<String, Object> row(String orderId) {
        return jdbc.queryForMap("SELECT status, expires_at, paid_at, payment_transaction_id, shipped_at, delivered_at, "
                + "cancelled_at, cancel_from, cancel_reason FROM orders WHERE id = ?", orderId);
    }

    private String text(String column, String orderId) {
        return jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", String.class, orderId);
    }

    private Instant instantOf(String column, String orderId) {
        Timestamp ts = jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", Timestamp.class, orderId);
        return ts == null ? null : ts.toInstant();
    }

    private Placed place(String userId, OrderLine... lines) {
        Order order = orderService.createOrder(userId, "key-" + UUID.randomUUID(), "구매자", "010-0000-0000", "서울", List.of(lines));
        return new Placed(userId, order.getId(), order.getTotalAmount());
    }

    private static Payment payment(String orderId, String status) {
        return new Payment(orderId, STORE_ID, status, 1000L, "KRW", "tx-" + orderId.substring(0, 8), "LIVE");
    }

    /** Placed, confirmed with the provider (status PAID there too) and the stub's counters cleared. */
    private Placed paid(String userId, OrderLine... lines) {
        Placed p = place(userId, lines);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW",
                "tx-" + p.orderId().substring(0, 8), "LIVE"));
        assertThat(paymentService.complete(p.orderId(), userId)).isEqualTo("PAID");
        gateway.findCalls.set(0);
        return p;
    }

    private Placed shipped(String userId, OrderLine... lines) {
        Placed p = paid(userId, lines);
        assertThat(orderService.shipOrder(p.orderId())).isEqualTo("SHIPPED");
        return p;
    }

    private Placed delivered(String userId, OrderLine... lines) {
        Placed p = shipped(userId, lines);
        assertThat(orderService.deliverOrder(p.orderId())).isEqualTo("DELIVERED");
        return p;
    }

    private void forceStatus(String orderId, String status) {
        jdbc.update("UPDATE orders SET status = ?, cancel_from = ? WHERE id = ?", status,
                "CANCELING".equals(status) ? "PAID" : null, orderId);
    }

    /** Customer cancel whose provider call timed out without effect: the order is left CANCELING (cancel_from PAID). */
    private void customerCancelInFlight(Placed p, String reason) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), reason))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
    }

    /** Admin refund whose provider call timed out without effect: the order is left CANCELING (cancel_from SHIPPED/DELIVERED). */
    private void refundInFlight(Placed p, String reason) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), reason))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
    }

    /** EXPIRED: overdue and swept by the real expiry job (stock given back). */
    private Placed expired(String userId, OrderLine... lines) {
        Placed p = place(userId, lines);
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), p.orderId());
        assertThat(paymentService.expirePending()).isEqualTo(1);
        return p;
    }

    /** PAYMENT_REVIEW through the real flow: expired, stock taken by others, then the late payment arrives. */
    private Placed paymentReview(String userId, String productId, int quantity, int stockLeft) {
        Placed p = expired(userId, line(productId, quantity));
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stockLeft, productId);
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW", "tx-review", "LIVE"));
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), userId)).isInstanceOf(PaymentNeedsReviewException.class);
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        gateway.findCalls.set(0);
        return p;
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static String eventBody(String type, String paymentId) {
        return "{\"type\":\"" + type + "\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"" + STORE_ID + "\",\"transactionId\":\"tx-1\"}}";
    }

    private void webhook(String body) throws Exception {
        String id = "msg_" + UUID.randomUUID();
        String ts = String.valueOf(NOW.getEpochSecond());
        paymentService.handleWebhook(id, ts, sign(id, ts, body), body);
    }

    private void webhookCancelled(String paymentId) throws Exception {
        webhook(eventBody("Transaction.Cancelled", paymentId));
    }

    private void providerSays(String orderId, String status) {
        gateway.payments.put(orderId, payment(orderId, status));
    }

    private void assertCode(Throwable t, Class<? extends com.shop.backend.domain.error.OrderStateException> type, String code) {
        assertThat(t).isInstanceOfSatisfying(type, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    /** The customer cancel is refused with ORDER_NOT_CANCELLABLE and neither the row, the stock nor the provider is touched. */
    private void assertCustomerCancelRefused(Placed p, String... productIds) {
        Map<String, Object> before = row(p.orderId());
        Map<String, Integer> stockBefore = stocks(productIds);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"));
        assertCode(thrown, OrderNotCancellableException.class, "ORDER_NOT_CANCELLABLE");
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stocks(productIds)).isEqualTo(stockBefore);
        assertThat(gateway.cancelCalls).isEmpty();
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

    /**
     * Holds the order row FOR UPDATE on a separate connection, starts all tasks, waits until every one of them is really
     * queued on that lock, then releases it: the real contention happens at the lock, not just "about the same time".
     */
    private List<Object> raceBehindRowLock(String orderId, List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            Connection holder = dataSource.getConnection();
            List<Future<Object>> futures = new ArrayList<>();
            try {
                holder.setAutoCommit(false);
                try (Statement st = holder.createStatement()) {
                    st.execute("SELECT 1 FROM orders WHERE id = '" + orderId + "' FOR UPDATE");
                }
                for (Callable<Object> task : tasks) {
                    futures.add(pool.submit(() -> {
                        try {
                            return task.call();
                        } catch (Throwable t) {
                            return t;
                        }
                    }));
                }
                awaitBlockedBackends(tasks.size());
            } finally {
                try {
                    holder.rollback();
                } finally {
                    holder.close();
                }
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

    private void awaitBlockedBackends(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("fewer than " + expected + " workers queued on the order row lock");
    }

    private static boolean failed(Object result) {
        return result instanceof Throwable;
    }

    private void newRound() {
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        gateway.reset();
    }

    private void insertRaw(String status, String cancelFrom, String cancelReason) {
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at, "
                        + "cancel_from, cancel_reason) VALUES (?, 'n', 'p', 'a', 1, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), status, Timestamp.valueOf(LocalDateTime.now()), cancelFrom, cancelReason);
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class);
    }

    // ------------------------------------------------------------------ 1. V16

    @Test
    void 성공_orders_status_valid는_아홉_가지_상태를_모두_받는다() {
        for (String status : List.of("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_REVIEW", "CANCELING", "CANCELLED",
                "SHIPPED", "DELIVERED", "REFUNDED")) {
            // orders_canceling_has_origin: a CANCELING row needs cancel_from
            assertThatCode(() -> insertRaw(status, "CANCELING".equals(status) ? "PAID" : null, null)).as(status)
                    .doesNotThrowAnyException();
        }
        assertThat(orderCount()).isEqualTo(9);
    }

    @Test
    void 실패_orders_status_valid는_알_수_없는_상태_소문자_빈_값_앞뒤_공백을_거부한다() {
        for (String status : List.of("UNKNOWN", "paid", "Cancelled", "cancelled", "", " ", "PAID ", " PAID", "CANCEL", "REFUND")) {
            assertThatThrownBy(() -> insertRaw(status, null, null)).as("[%s]", status)
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_status_valid");
        }
        assertThat(orderCount()).isZero();
    }

    @Test
    void 실패_기존_주문을_알_수_없는_상태로_바꾸는_UPDATE도_거부하고_새_상태로는_바꿀_수_있다() {
        insertRaw("PAID", null, null);

        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'canceled'"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_status_valid");
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("PAID");

        assertThat(jdbc.update("UPDATE orders SET status = 'REFUNDED'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders", String.class)).isEqualTo("REFUNDED");
    }

    @Test
    void 성공_orders_cancel_from_valid는_PAID_SHIPPED_DELIVERED와_NULL을_받는다() {
        for (String from : new String[]{"PAID", "SHIPPED", "DELIVERED", null}) {
            // NULL is only legal when the row is not CANCELING (orders_canceling_has_origin)
            assertThatCode(() -> insertRaw(from == null ? "PAID" : "CANCELING", from, null)).as("[%s]", from)
                    .doesNotThrowAnyException();
        }
        assertThat(orderCount()).isEqualTo(4);
    }

    @Test
    void 실패_orders_cancel_from_valid는_PAID_SHIPPED_DELIVERED_밖의_값을_거부한다() {
        for (String from : new String[]{"CANCELING", "CANCELLED", "REFUNDED", "PENDING_PAYMENT", "EXPIRED", "PAYMENT_REVIEW",
                "paid", "shipped", "", "PAID ", "UNKNOWN"}) {
            assertThatThrownBy(() -> insertRaw("CANCELING", from, null)).as("[%s]", from)
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_cancel_from_valid");
        }
        assertThat(orderCount()).isZero();
    }

    @Test
    void 성공_새_컬럼_다섯_개는_NULL을_허용하고_타입과_길이가_맞다() {
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable "
                + "FROM information_schema.columns WHERE table_name = 'orders' AND column_name IN "
                + "('shipped_at', 'delivered_at', 'cancelled_at', 'cancel_from', 'cancel_reason') ORDER BY column_name");

        assertThat(columns).hasSize(5);
        assertThat(columns).allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));
        assertThat(columns.get(0)).containsEntry("column_name", "cancel_from")
                .containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 20);
        assertThat(columns.get(1)).containsEntry("column_name", "cancel_reason")
                .containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 200);
        assertThat(columns.get(2)).containsEntry("column_name", "cancelled_at").containsEntry("data_type", "timestamp with time zone");
        assertThat(columns.get(3)).containsEntry("column_name", "delivered_at").containsEntry("data_type", "timestamp with time zone");
        assertThat(columns.get(4)).containsEntry("column_name", "shipped_at").containsEntry("data_type", "timestamp with time zone");
    }

    @Test
    void 실패_cancel_reason은_200자까지만_들어간다() {
        assertThatCode(() -> insertRaw("CANCELLED", null, "가".repeat(200))).doesNotThrowAnyException();

        assertThatThrownBy(() -> insertRaw("CANCELLED", null, "가".repeat(201)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void 성공_기존_PAID_행은_새_컬럼이_NULL이어도_유효하고_그대로_배송_처리할_수_있다() {
        // an order as it looked before V16: only the old columns, user_id NULL
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                + "VALUES (?, 'n', 'p', 'a', 1, 'PAID', ?)", id, Timestamp.valueOf(LocalDateTime.now()));

        Map<String, Object> row = row(id);
        assertThat(row).containsEntry("status", "PAID").containsEntry("shipped_at", null).containsEntry("delivered_at", null)
                .containsEntry("cancelled_at", null).containsEntry("cancel_from", null).containsEntry("cancel_reason", null);
        assertThat(jdbc.update("UPDATE orders SET customer_name = 'changed' WHERE id = ?", id)).isEqualTo(1);

        assertThat(orderService.shipOrder(id)).isEqualTo("SHIPPED");
        assertThat(instantOf("shipped_at", id)).isEqualTo(NOW);
    }

    @Test
    void 실패_주인이_없는_기존_PAID_주문을_고객이_취소하려_하면_404_동작이고_행은_그대로다() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                + "VALUES (?, 'n', 'p', 'a', 1, 'PAID', ?)", id, Timestamp.valueOf(LocalDateTime.now()));
        String someone = data.user();
        Map<String, Object> before = row(id);

        assertThatThrownBy(() -> cancelService.cancelByCustomer(id, someone, "사유")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(id, null, "사유")).isInstanceOf(OrderNotFoundException.class);

        assertThat(row(id)).isEqualTo(before);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 2. customer cancel, happy path

    @Test
    void 성공_PAID_주문을_취소하면_CANCELLED가_되고_모든_품목의_재고가_정확히_한_번_돌아온다() {
        product("p1", 10);
        product("p2", 10);
        product("p3", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 4), line("p3", 1));
        Map<String, Object> before = row(p.orderId());
        assertThat(stocks("p1", "p2", "p3")).isEqualTo(Map.of("p1", 7, "p2", 6, "p3", 9));

        String result = cancelService.cancelByCustomer(p.orderId(), p.userId(), "단순 변심");

        assertThat(result).isEqualTo("CANCELLED");
        Map<String, Object> after = row(p.orderId());
        assertThat(after).containsEntry("status", "CANCELLED").containsEntry("cancel_reason", "단순 변심");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(after.get("paid_at")).isEqualTo(before.get("paid_at"));
        assertThat(after.get("payment_transaction_id")).isEqualTo(before.get("payment_transaction_id"));
        assertThat(after.get("shipped_at")).isNull();
        assertThat(after.get("delivered_at")).isNull();
        assertThat(stocks("p1", "p2", "p3")).isEqualTo(Map.of("p1", 10, "p2", 10, "p3", 10));
        assertThat(gateway.cancelCalls).containsExactly(new CancelCall(p.orderId(), "단순 변심", Requester.CUSTOMER));
    }

    @Test
    void 성공_같은_상품을_여러_줄로_주문해도_합산된_수량이_한_번에_돌아온다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 2), line("p1", 1));
        assertThat(stock("p1")).isEqualTo(7);

        cancelService.cancelByCustomer(p.orderId(), p.userId(), null);

        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_재고는_절대값으로_덮어쓰지_않고_현재_재고에_더해진다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        jdbc.update("UPDATE products SET stock = 2 WHERE id = 'p1'"); // other buyers took more in the meantime

        cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유");

        assertThat(stock("p1")).isEqualTo(5);
    }

    @Test
    void 성공_사유를_생략하거나_공백이면_포트원에는_기본_사유로_보낸다() {
        product("p1", 10);
        Placed a = paid(data.user(), line("p1", 1));
        Placed b = paid(data.user(), line("p1", 1));

        cancelService.cancelByCustomer(a.orderId(), a.userId(), null);
        cancelService.cancelByCustomer(b.orderId(), b.userId(), "   ");

        assertThat(gateway.cancelCalls).containsExactly(
                new CancelCall(a.orderId(), DEFAULT_REASON, Requester.CUSTOMER),
                new CancelCall(b.orderId(), DEFAULT_REASON, Requester.CUSTOMER));
        assertThat(status(a.orderId())).isEqualTo("CANCELLED");
        assertThat(status(b.orderId())).isEqualTo("CANCELLED");
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_사유는_앞뒤_공백을_잘라_저장하고_보내며_200자는_받고_201자는_거부한다() {
        product("p1", 10);
        Placed trimmed = paid(data.user(), line("p1", 1));
        Placed max = paid(data.user(), line("p1", 1));
        Placed tooLong = paid(data.user(), line("p1", 1));

        cancelService.cancelByCustomer(trimmed.orderId(), trimmed.userId(), "  변심  ");
        cancelService.cancelByCustomer(max.orderId(), max.userId(), "나".repeat(200));
        Map<String, Object> before = row(tooLong.orderId());
        int callsBefore = gateway.cancelCalls.size();
        assertThatThrownBy(() -> cancelService.cancelByCustomer(tooLong.orderId(), tooLong.userId(), "나".repeat(201)))
                .isInstanceOf(ValidationException.class);

        assertThat(text("cancel_reason", trimmed.orderId())).isEqualTo("변심");
        assertThat(gateway.cancelCalls.get(0).reason()).isEqualTo("변심");
        assertThat(text("cancel_reason", max.orderId())).isEqualTo("나".repeat(200));
        assertThat(gateway.cancelCalls).hasSize(callsBefore);
        assertThat(row(tooLong.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(10 - 3 + 2);
    }

    @Test
    void 성공_취소를_또_요청해도_200_멱등이고_재고는_다시_늘지_않으며_포트원도_다시_부르지_않는다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        cancelService.cancelByCustomer(p.orderId(), p.userId(), "처음 사유");
        Map<String, Object> afterFirst = row(p.orderId());
        Map<String, Integer> stocksAfterFirst = stocks("p1", "p2");

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "다른 사유")).isEqualTo("CANCELLED");
        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), null)).isEqualTo("CANCELLED");

        assertThat(row(p.orderId())).isEqualTo(afterFirst);
        assertThat(stocks("p1", "p2")).isEqualTo(stocksAfterFirst).isEqualTo(Map.of("p1", 10, "p2", 10));
        assertThat(gateway.cancelCalls).hasSize(1);
    }

    // ------------------------------------------------------------------ 3. customer cancel refusals leave the DB untouched

    @Test
    void 실패_다른_사용자의_주문은_404_동작이고_행_재고_포트원_모두_그대로다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        String stranger = data.user();
        Map<String, Object> before = row(p.orderId());

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), stranger, "사유")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), "", "사유")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), "  ", "사유")).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), null, "사유")).isInstanceOf(OrderNotFoundException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).isEmpty();
        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).as("the owner still can").isEqualTo("CANCELLED");
    }

    @Test
    void 실패_없는_주문은_404_동작이고_포트원을_부르지_않는다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        assertThatThrownBy(() -> cancelService.cancelByCustomer(UUID.randomUUID().toString(), p.userId(), "사유"))
                .isInstanceOf(OrderNotFoundException.class);

        assertThat(gateway.cancelCalls).isEmpty();
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_SHIPPED_주문은_ORDER_NOT_CANCELLABLE이고_아무것도_바뀌지_않는다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("SHIPPED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_DELIVERED_주문은_ORDER_NOT_CANCELLABLE이고_아무것도_바뀌지_않는다() {
        product("p1", 10);
        Placed p = delivered(data.user(), line("p1", 3));

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("DELIVERED");
    }

    @Test
    void 실패_REFUNDED_주문은_ORDER_NOT_CANCELLABLE이고_재고는_다시_늘지_않는다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        cancelService.refundByAdmin(p.orderId(), "하자");
        gateway.cancelCalls.clear();

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_PENDING_PAYMENT_주문은_ORDER_NOT_CANCELLABLE이고_재고_예약은_그대로다() {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("PENDING_PAYMENT");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_EXPIRED_주문은_ORDER_NOT_CANCELLABLE이고_재고는_다시_늘지_않는다() {
        product("p1", 10);
        Placed p = expired(data.user(), line("p1", 3));
        assertThat(stock("p1")).isEqualTo(10);

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 실패_PAYMENT_REVIEW_주문은_ORDER_NOT_CANCELLABLE이고_재고는_그대로다() {
        product("p1", 10);
        Placed p = paymentReview(data.user(), "p1", 3, 2);

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("PAYMENT_REVIEW");
        assertThat(stock("p1")).isEqualTo(2);
    }

    @Test
    void 실패_판매자_환불이_진행_중인_SHIPPED_출신_CANCELING_주문을_고객이_이어받을_수_없다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        refundInFlight(p, "하자");
        assertThat(text("cancel_from", p.orderId())).isEqualTo("SHIPPED");

        assertCustomerCancelRefused(p, "p1");
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
        assertThat(text("cancel_from", p.orderId())).isEqualTo("SHIPPED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_판매자_환불이_진행_중인_DELIVERED_출신_CANCELING_주문도_고객이_이어받을_수_없다() {
        product("p1", 10);
        Placed p = delivered(data.user(), line("p1", 3));
        refundInFlight(p, "하자");
        assertThat(text("cancel_from", p.orderId())).isEqualTo("DELIVERED");

        assertCustomerCancelRefused(p, "p1");
        assertThat(text("cancel_from", p.orderId())).isEqualTo("DELIVERED");
    }

    @Test
    void 실패_결제_설정이_비어_있으면_PAYMENT_NOT_CONFIGURED이고_주문은_PAID로_그대로다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());
        gateway.configured = false;

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"))
                .isInstanceOf(PaymentNotConfiguredException.class);
        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "사유")).isInstanceOf(PaymentNotConfiguredException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 4. provider failure paths

    @Test
    void 실패_포트원이_오류로_거절하면_PAID로_되돌아가고_재고는_그대로이며_503_예외가_전달된다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        Map<String, Object> before = row(p.orderId());
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class, e -> {
                    assertThat(e.isOutcomeUnknown()).isFalse();
                    assertThat(e.getCode()).isEqualTo("CANCEL_PROVIDER_UNAVAILABLE");
                });

        assertThat(row(p.orderId())).isEqualTo(before); // PAID again: cancel_from, cancel_reason and cancelled_at are NULL
        assertThat(text("cancel_from", p.orderId())).isNull();
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 7, "p2", 8));
        assertThat(gateway.cancelCalls).hasSize(1);
    }

    @Test
    void 성공_거절로_되돌아간_주문은_포트원이_복구되면_다시_취소할_수_있고_재고는_한_번만_돌아온다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");

        assertThat(stock("p1")).isEqualTo(10);
        assertThat(gateway.cancelCalls).hasSize(2);
    }

    @Test
    void 실패_결과를_알_수_없는_타임아웃이면_CANCELING으로_남고_재고는_차감된_채_그대로다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class, e -> assertThat(e.isOutcomeUnknown()).isTrue());

        Map<String, Object> row = row(p.orderId());
        assertThat(row).containsEntry("status", "CANCELING").containsEntry("cancel_from", "PAID").containsEntry("cancel_reason", "사유");
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 7, "p2", 8));
    }

    @Test
    void 성공_타임아웃으로_CANCELING에_남은_주문은_재시도하면_이미_취소됨_응답으로_CANCELLED가_되고_재고는_한_번만_돌아온다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_APPLIED); // the provider did cancel, the answer got lost
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isInstanceOf(CancelProviderUnavailableException.class);
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 7, "p2", 8));
        gateway.cancelModes.put(p.orderId(), CancelMode.OK); // now answers "already cancelled" as a success

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 10, "p2", 10));
        assertThat(gateway.cancelCalls).hasSize(2);

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");
        assertThat(stocks("p1", "p2")).as("a third request restores nothing").isEqualTo(Map.of("p1", 10, "p2", 10));
        assertThat(gateway.cancelCalls).hasSize(2);
    }

    @Test
    void 성공_CANCELING_주문을_다시_요청했는데_포트원이_거절하면_PAID로_되돌아간다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        customerCancelInFlight(p, "사유");
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isInstanceOf(CancelProviderUnavailableException.class);

        assertThat(status(p.orderId())).isEqualTo("PAID");
        assertThat(text("cancel_from", p.orderId())).isNull();
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_Transaction_Cancelled_웹훅이_CANCELING_주문을_CANCELLED로_마무리하고_재고는_한_번만_돌아온다() throws Exception {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_APPLIED);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        assertThat(status(p.orderId())).isEqualTo("CANCELING");

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 10, "p2", 10));
        webhookCancelled(p.orderId()); // the provider redelivers
        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 10, "p2", 10));
        assertThat(gateway.cancelCalls).as("the webhook path never cancels at the provider").isEmpty();
    }

    @Test
    void 실패_웹훅이_왔어도_포트원_조회가_CANCELLED가_아니면_CANCELING_주문은_그대로다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        customerCancelInFlight(p, "사유"); // the stub still reports the payment as PAID
        Map<String, Object> before = row(p.orderId());

        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    // ------------------------------------------------------------------ 5. webhook Transaction.Cancelled / PartialCancelled

    @Test
    void 성공_웹훅_PAID_주문은_CANCELLED가_되고_재고가_돌아오며_포트원_취소는_부르지_않는다() throws Exception {
        product("p1", 10);
        product("p2", 10);
        Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
        providerSays(p.orderId(), "CANCELLED"); // cancelled in the provider console

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 10, "p2", 10));
        assertThat(gateway.cancelCalls).isEmpty();
    }

    @Test
    void 성공_웹훅_SHIPPED_주문은_REFUNDED가_되고_재고는_복구하지_않는다() throws Exception {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(instantOf("shipped_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_웹훅_DELIVERED_주문은_REFUNDED가_되고_재고는_복구하지_않는다() throws Exception {
        product("p1", 10);
        Placed p = delivered(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(instantOf("delivered_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_웹훅_판매자_환불_중인_SHIPPED_출신_CANCELING_주문은_REFUNDED가_되고_재고는_복구하지_않는다() throws Exception {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        refundInFlight(p, "하자");
        providerSays(p.orderId(), "CANCELLED");

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_웹훅_PAYMENT_REVIEW_주문은_CANCELLED로_마무리하되_재고는_복구하지_않는다() throws Exception {
        product("p1", 10);
        Placed p = paymentReview(data.user(), "p1", 3, 2);
        providerSays(p.orderId(), "CANCELLED");

        webhookCancelled(p.orderId());

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).as("it never held stock").isEqualTo(2);
    }

    @Test
    void 성공_웹훅_PENDING_PAYMENT_주문은_상태도_재고도_바꾸지_않는다() throws Exception {
        product("p1", 10);
        Placed p = place(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");
        Map<String, Object> before = row(p.orderId());

        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_웹훅_EXPIRED_주문은_상태도_재고도_바꾸지_않는다() throws Exception {
        product("p1", 10);
        Placed p = expired(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");
        Map<String, Object> before = row(p.orderId());

        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_이미_CANCELLED인_주문에_웹훅이_거듭_와도_재고는_늘지_않는다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유");
        Map<String, Object> after = row(p.orderId());
        assertThat(stock("p1")).isEqualTo(10);

        webhookCancelled(p.orderId());
        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(after);
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_이미_REFUNDED인_주문에_웹훅이_와도_아무것도_바뀌지_않는다() throws Exception {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        cancelService.refundByAdmin(p.orderId(), "하자");
        Map<String, Object> after = row(p.orderId());

        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(after);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_웹훅_후_고객이_취소를_눌러도_이미_CANCELLED라_재고는_한_번만_돌아온다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");
        webhookCancelled(p.orderId());

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");

        assertThat(stock("p1")).isEqualTo(10);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    @Test
    void 실패_웹훅이_와도_포트원_조회가_CANCELLED가_아니면_주문은_그대로다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        for (String providerStatus : new String[]{"PAID", "PARTIAL_CANCELLED", "READY", "FAILED", "PENDING", "cancelled"}) {
            providerSays(p.orderId(), providerStatus);
            webhookCancelled(p.orderId());
            assertThat(row(p.orderId())).as(providerStatus).isEqualTo(before);
            assertThat(stock("p1")).as(providerStatus).isEqualTo(7);
        }
        gateway.payments.remove(p.orderId()); // the provider knows no such payment
        webhookCancelled(p.orderId());
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_웹훅의_조회_결과가_다른_결제_id이거나_다른_상점이면_무시한다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        gateway.payments.put(p.orderId(), new Payment("another-payment", STORE_ID, "CANCELLED", 1000L, "KRW", "tx", "LIVE"));
        webhookCancelled(p.orderId());
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), "someone-elses-store", "CANCELLED", 1000L, "KRW", "tx", "LIVE"));
        webhookCancelled(p.orderId());
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), null, "CANCELLED", 1000L, "KRW", "tx", "LIVE"));
        webhookCancelled(p.orderId());

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_우리_주문이_아닌_paymentId의_취소_웹훅은_무시하고_포트원도_부르지_않는다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        assertThatCode(() -> webhookCancelled(UUID.randomUUID().toString())).doesNotThrowAnyException();

        assertThat(gateway.findCalls.get()).isZero();
        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_PartialCancelled_웹훅은_포트원이_CANCELLED라고_해도_주문_상태를_바꾸지_않는다() throws Exception {
        product("p1", 10);
        Placed paidOrder = paid(data.user(), line("p1", 3));
        Placed shippedOrder = shipped(data.user(), line("p1", 1));
        providerSays(paidOrder.orderId(), "CANCELLED");
        providerSays(shippedOrder.orderId(), "PARTIAL_CANCELLED");
        Map<String, Object> paidBefore = row(paidOrder.orderId());
        Map<String, Object> shippedBefore = row(shippedOrder.orderId());

        webhook(eventBody("Transaction.PartialCancelled", paidOrder.orderId()));
        webhook(eventBody("Transaction.PartialCancelled", shippedOrder.orderId()));

        assertThat(row(paidOrder.orderId())).isEqualTo(paidBefore);
        assertThat(row(shippedOrder.orderId())).isEqualTo(shippedBefore);
        assertThat(stock("p1")).isEqualTo(6);
    }

    @Test
    void 실패_서명이_틀린_취소_웹훅은_ValidationException이고_주문은_그대로다() throws Exception {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED"); // would be applied if the signature were accepted
        Map<String, Object> before = row(p.orderId());
        String body = eventBody("Transaction.Cancelled", p.orderId());
        String ts = String.valueOf(NOW.getEpochSecond());

        assertThatThrownBy(() -> paymentService.handleWebhook("msg_1", ts, sign("msg_other", ts, body), body))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> paymentService.handleWebhook("msg_1", ts, null, body)).isInstanceOf(ValidationException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.findCalls.get()).isZero();
    }

    @Test
    void 실패_취소_웹훅에서_포트원_조회가_장애면_예외로_전달되고_주문은_그대로다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        providerSays(p.orderId(), "CANCELLED");
        gateway.unavailable = true;
        Map<String, Object> before = row(p.orderId());

        assertThatThrownBy(() -> webhookCancelled(p.orderId())).isInstanceOf(PaymentProviderUnavailableException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    // ------------------------------------------------------------------ 6. admin: ship / deliver / refund

    @Test
    void 성공_배송_시작은_PAID를_SHIPPED로_바꾸고_shipped_at을_기록하며_재고는_그대로다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        assertThat(orderService.shipOrder(p.orderId())).isEqualTo("SHIPPED");

        Map<String, Object> after = row(p.orderId());
        assertThat(after).containsEntry("status", "SHIPPED");
        assertThat(instantOf("shipped_at", p.orderId())).isEqualTo(NOW);
        assertThat(after.get("delivered_at")).isNull();
        assertThat(after.get("cancelled_at")).isNull();
        assertThat(after.get("paid_at")).isEqualTo(before.get("paid_at"));
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    @Test
    void 성공_배송_완료는_SHIPPED를_DELIVERED로_바꾸고_delivered_at을_기록하며_shipped_at은_그대로다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));

        assertThat(orderService.deliverOrder(p.orderId())).isEqualTo("DELIVERED");

        assertThat(status(p.orderId())).isEqualTo("DELIVERED");
        assertThat(instantOf("delivered_at", p.orderId())).isEqualTo(NOW);
        assertThat(instantOf("shipped_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_PAID가_아닌_주문의_배송_시작은_INVALID_ORDER_STATE이고_행과_재고는_그대로다() {
        product("p1", 10);
        for (String status : List.of("PENDING_PAYMENT", "EXPIRED", "PAYMENT_REVIEW", "CANCELING", "CANCELLED", "SHIPPED", "DELIVERED", "REFUNDED")) {
            Placed p = paid(data.user(), line("p1", 1));
            forceStatus(p.orderId(), status);
            Map<String, Object> before = row(p.orderId());
            Map<String, Integer> stockBefore = stocks("p1");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> orderService.shipOrder(p.orderId()));

            assertCode(thrown, InvalidOrderStateException.class, "INVALID_ORDER_STATE");
            assertThat(row(p.orderId())).as(status).isEqualTo(before);
            assertThat(stocks("p1")).as(status).isEqualTo(stockBefore);
        }
    }

    @Test
    void 실패_SHIPPED가_아닌_주문의_배송_완료는_INVALID_ORDER_STATE이고_행은_그대로다() {
        product("p1", 10);
        for (String status : List.of("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_REVIEW", "CANCELING", "CANCELLED", "DELIVERED", "REFUNDED")) {
            Placed p = paid(data.user(), line("p1", 1));
            forceStatus(p.orderId(), status);
            Map<String, Object> before = row(p.orderId());

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> orderService.deliverOrder(p.orderId()));

            assertCode(thrown, InvalidOrderStateException.class, "INVALID_ORDER_STATE");
            assertThat(row(p.orderId())).as(status).isEqualTo(before);
        }
    }

    @Test
    void 실패_없는_주문의_배송_시작_배송_완료_환불은_OrderNotFoundException이다() {
        String missing = UUID.randomUUID().toString();

        assertThatThrownBy(() -> orderService.shipOrder(missing)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> orderService.deliverOrder(missing)).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> cancelService.refundByAdmin(missing, "사유")).isInstanceOf(OrderNotFoundException.class);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    @Test
    void 실패_배송이_시작된_주문은_두_번_배송_시작을_할_수_없고_shipped_at이_유지된다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        Instant shippedAt = instantOf("shipped_at", p.orderId());

        assertThatThrownBy(() -> orderService.shipOrder(p.orderId())).isInstanceOf(InvalidOrderStateException.class);

        assertThat(instantOf("shipped_at", p.orderId())).isEqualTo(shippedAt);
        assertThat(status(p.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void 성공_SHIPPED_주문_환불은_REFUNDED가_되고_판매자_요청으로_포트원을_부르며_재고는_복구하지_않는다() {
        product("p1", 10);
        product("p2", 10);
        Placed p = shipped(data.user(), line("p1", 3), line("p2", 2));
        Map<String, Object> before = row(p.orderId());

        String result = cancelService.refundByAdmin(p.orderId(), "  상품 하자  ");

        assertThat(result).isEqualTo("REFUNDED");
        Map<String, Object> after = row(p.orderId());
        assertThat(after).containsEntry("status", "REFUNDED").containsEntry("cancel_reason", "상품 하자");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(after.get("shipped_at")).isEqualTo(before.get("shipped_at"));
        assertThat(after.get("paid_at")).isEqualTo(before.get("paid_at"));
        assertThat(stocks("p1", "p2")).isEqualTo(Map.of("p1", 7, "p2", 8));
        assertThat(gateway.cancelCalls).containsExactly(new CancelCall(p.orderId(), "상품 하자", Requester.ADMIN));
    }

    @Test
    void 성공_DELIVERED_주문_환불도_REFUNDED가_되고_재고는_복구하지_않는다() {
        product("p1", 10);
        Placed p = delivered(data.user(), line("p1", 3));

        assertThat(cancelService.refundByAdmin(p.orderId(), "반품")).isEqualTo("REFUNDED");

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(instantOf("delivered_at", p.orderId())).isEqualTo(NOW);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).containsExactly(new CancelCall(p.orderId(), "반품", Requester.ADMIN));
    }

    @Test
    void 성공_이미_REFUNDED인_주문을_다시_환불해도_멱등이고_포트원도_다시_부르지_않는다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        cancelService.refundByAdmin(p.orderId(), "첫 사유");
        Map<String, Object> after = row(p.orderId());

        assertThat(cancelService.refundByAdmin(p.orderId(), "다른 사유")).isEqualTo("REFUNDED");

        assertThat(row(p.orderId())).isEqualTo(after);
        assertThat(gateway.cancelCalls).hasSize(1);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_배송_전_상태의_환불은_INVALID_ORDER_STATE이고_포트원도_부르지_않으며_행과_재고는_그대로다() {
        product("p1", 10);
        for (String status : List.of("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_REVIEW", "CANCELLED")) {
            Placed p = paid(data.user(), line("p1", 1));
            forceStatus(p.orderId(), status);
            Map<String, Object> before = row(p.orderId());
            Map<String, Integer> stockBefore = stocks("p1");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> cancelService.refundByAdmin(p.orderId(), "사유"));

            assertCode(thrown, InvalidOrderStateException.class, "INVALID_ORDER_STATE");
            assertThat(row(p.orderId())).as(status).isEqualTo(before);
            assertThat(stocks("p1")).as(status).isEqualTo(stockBefore);
        }
        assertThat(gateway.cancelCalls).isEmpty();
    }

    @Test
    void 성공_멈춘_PAID_출신_CANCELING_주문은_판매자_환불이_ADMIN으로_이어받아_CANCELLED와_재고_복구로_끝낸다() {
        product("p1", 10);
        Placed p = paid(data.user(), line("p1", 3));
        customerCancelInFlight(p, "고객 사유");

        assertThat(cancelService.refundByAdmin(p.orderId(), "판매자 사유")).isEqualTo("CANCELLED");

        assertThat(status(p.orderId())).isEqualTo("CANCELLED");
        assertThat(gateway.cancelCalls).containsExactly(new CancelCall(p.orderId(), "판매자 사유", Requester.ADMIN));
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 실패_환불_사유가_없거나_공백이거나_201자면_ValidationException이고_포트원을_부르지_않는다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());

        for (String reason : new String[]{null, "", "   ", "\t\n", "다".repeat(201)}) {
            assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), reason)).as("[%s]", reason).isInstanceOf(ValidationException.class);
        }

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(gateway.cancelCalls).isEmpty();
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 성공_환불_사유는_200자까지_받아_그대로_저장한다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));

        assertThat(cancelService.refundByAdmin(p.orderId(), "다".repeat(200))).isEqualTo("REFUNDED");

        assertThat(text("cancel_reason", p.orderId())).isEqualTo("다".repeat(200));
    }

    @Test
    void 실패_SHIPPED_환불에서_포트원이_거절하면_SHIPPED로_되돌아가고_행은_환불_전과_같다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "하자"))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class, e -> assertThat(e.isOutcomeUnknown()).isFalse());

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).hasSize(1);
    }

    @Test
    void 실패_DELIVERED_환불에서_포트원이_거절하면_DELIVERED로_되돌아간다() {
        product("p1", 10);
        Placed p = delivered(data.user(), line("p1", 3));
        Map<String, Object> before = row(p.orderId());
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "하자")).isInstanceOf(CancelProviderUnavailableException.class);

        assertThat(row(p.orderId())).isEqualTo(before);
        assertThat(status(p.orderId())).isEqualTo("DELIVERED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_환불에서_결과를_알_수_없는_타임아웃이면_CANCELING으로_남고_재시도가_REFUNDED로_마무리한다() {
        product("p1", 10);
        Placed p = shipped(data.user(), line("p1", 3));
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_APPLIED);

        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "하자"))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class, e -> assertThat(e.isOutcomeUnknown()).isTrue());

        assertThat(row(p.orderId())).containsEntry("status", "CANCELING").containsEntry("cancel_from", "SHIPPED").containsEntry("cancel_reason", "하자");
        assertThat(stock("p1")).isEqualTo(7);
        gateway.cancelModes.put(p.orderId(), CancelMode.OK);

        assertThat(cancelService.refundByAdmin(p.orderId(), "하자")).isEqualTo("REFUNDED");

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).hasSize(2);
    }

    // ------------------------------------------------------------------ 7. races with real threads

    /** Cancel and ship raced on one PAID order: exactly one wins and the stock matches the winner. */
    private void assertCancelOrShipExactlyOne(Placed p, List<Object> results, String context) {
        boolean cancelWon = !failed(results.get(0));
        boolean shipWon = !failed(results.get(1));
        assertThat(cancelWon ^ shipWon).as("%s: exactly one of cancel and ship wins: %s", context, results).isTrue();
        if (cancelWon) {
            assertThat(results.get(1)).as(context).isInstanceOf(InvalidOrderStateException.class);
            assertThat(status(p.orderId())).as(context).isEqualTo("CANCELLED");
            assertThat(stock("p1")).as("%s: stock given back once", context).isEqualTo(10);
            assertThat(instantOf("shipped_at", p.orderId())).as(context).isNull();
            assertThat(gateway.cancelCalls).as(context).hasSize(1);
        } else {
            assertThat(results.get(0)).as(context).isInstanceOf(OrderNotCancellableException.class);
            assertThat(status(p.orderId())).as(context).isEqualTo("SHIPPED");
            assertThat(stock("p1")).as("%s: stock stays reserved", context).isEqualTo(7);
            assertThat(instantOf("cancelled_at", p.orderId())).as(context).isNull();
            assertThat(gateway.cancelCalls).as("%s: a shipped order is never cancelled at the provider", context).isEmpty();
        }
    }

    @Test
    void 성공_고객_취소와_배송_시작이_동시에_와도_하나만_성공하고_재고가_승자와_맞는다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            product("p1", 10);
            Placed p = paid(data.user(), line("p1", 3));

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> orderService.shipOrder(p.orderId())));

            assertCancelOrShipExactlyOne(p, results, "round " + round);
        }
    }

    @Test
    void 성공_행_잠금에_둘_다_줄을_선_상태에서_풀어도_고객_취소와_배송_시작은_하나만_성공한다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            product("p1", 10);
            Placed p = paid(data.user(), line("p1", 3));

            List<Object> results = raceBehindRowLock(p.orderId(), List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> orderService.shipOrder(p.orderId())));

            assertCancelOrShipExactlyOne(p, results, "round " + round);
        }
    }

    @Test
    void 성공_고객_취소를_세_번_동시에_보내도_모두_CANCELLED이고_재고는_정확히_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            product("p1", 10);
            product("p2", 10);
            Placed p = paid(data.user(), line("p1", 3), line("p2", 2));

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")));

            assertThat(results).as("round %d", round).containsOnly("CANCELLED");
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stocks("p1", "p2")).as("round %d: restored exactly once", round).isEqualTo(Map.of("p1", 10, "p2", 10));
            assertThat(gateway.cancelCalls).as("round %d", round).isNotEmpty().allSatisfy(c -> assertThat(c.requester()).isEqualTo(Requester.CUSTOMER));
        }
    }

    @Test
    void 성공_행_잠금에_세_요청이_줄을_선_상태에서_풀어도_재고는_정확히_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            product("p1", 10);
            Placed p = paid(data.user(), line("p1", 3));

            List<Object> results = raceBehindRowLock(p.orderId(), List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")));

            assertThat(results).as("round %d", round).containsOnly("CANCELLED");
            assertThat(stock("p1")).as("round %d", round).isEqualTo(10);
        }
    }

    @Test
    void 성공_고객_취소와_취소_웹훅이_동시에_와도_CANCELLED이고_재고는_정확히_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            product("p1", 10);
            product("p2", 10);
            Placed p = paid(data.user(), line("p1", 3), line("p2", 2));
            providerSays(p.orderId(), "CANCELLED"); // cancelled in the console, the customer presses cancel at the same time

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook";
                    },
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook";
                    }));

            assertThat(results.stream().filter(OrderCancelIntegrationTest::failed).toList()).as("round %d", round).isEmpty();
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stocks("p1", "p2")).as("round %d: restored exactly once", round).isEqualTo(Map.of("p1", 10, "p2", 10));
        }
    }

    @Test
    void 성공_행_잠금에_취소와_웹훅이_줄을_선_상태에서_풀어도_재고는_정확히_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            product("p1", 10);
            Placed p = paid(data.user(), line("p1", 3));
            providerSays(p.orderId(), "CANCELLED");

            List<Object> results = raceBehindRowLock(p.orderId(), List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook";
                    },
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook";
                    }));

            assertThat(results.stream().filter(OrderCancelIntegrationTest::failed).toList()).as("round %d: %s", round, results).isEmpty();
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stock("p1")).as("round %d", round).isEqualTo(10);
        }
    }

    @Test
    void 성공_고객_취소와_관리자_환불_시도가_PAID_주문에서_동시에_와도_거부되거나_이어받을_뿐_취소는_한_번만_반영된다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            product("p1", 10);
            Placed p = paid(data.user(), line("p1", 3));

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> cancelService.refundByAdmin(p.orderId(), "판매자 사유")));

            // The seller either came first (PAID: refused) or found the customer's CANCELING order and resumed it.
            assertThat(results.get(0)).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(results.get(1)).as("round %d", round)
                    .satisfiesAnyOf(r -> assertThat(r).isInstanceOf(InvalidOrderStateException.class),
                            r -> assertThat(r).isEqualTo("CANCELLED"));
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stock("p1")).as("round %d: restored exactly once", round).isEqualTo(10);
            assertThat(gateway.cancelCalls).as("round %d", round).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        }
    }

    @Test
    void 성공_관리자_환불을_세_번_동시에_보내도_모두_REFUNDED이고_재고는_그대로다() throws Exception {
        for (int round = 0; round < 15; round++) {
            newRound();
            product("p1", 10);
            Placed p = shipped(data.user(), line("p1", 3));

            List<Object> results = runTogether(List.of(
                    () -> cancelService.refundByAdmin(p.orderId(), "하자"),
                    () -> cancelService.refundByAdmin(p.orderId(), "하자"),
                    () -> cancelService.refundByAdmin(p.orderId(), "하자")));

            assertThat(results).as("round %d", round).containsOnly("REFUNDED");
            assertThat(status(p.orderId())).as("round %d", round).isEqualTo("REFUNDED");
            assertThat(stock("p1")).as("round %d: a refund after shipping never restores stock", round).isEqualTo(7);
            assertThat(gateway.cancelCalls).as("round %d", round).isNotEmpty()
                    .allSatisfy(c -> assertThat(c.requester()).isEqualTo(Requester.ADMIN));
        }
    }

    @Test
    void 성공_고객_취소와_취소_웹훅이_동시에_와도_다른_주문의_재고는_건드리지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            product("p1", 10);
            Placed target = paid(data.user(), line("p1", 3));
            Placed other = paid(data.user(), line("p1", 2));
            providerSays(target.orderId(), "CANCELLED");

            runTogether(List.of(
                    () -> cancelService.cancelByCustomer(target.orderId(), target.userId(), "사유"),
                    () -> {
                        webhookCancelled(target.orderId());
                        return null;
                    }));

            assertThat(status(other.orderId())).as("round %d", round).isEqualTo("PAID");
            assertThat(stock("p1")).as("round %d: only the target's 3 units come back", round).isEqualTo(10 - 3 - 2 + 3);
        }
    }

    // ------------------------------------------------------------------ 8. admin list on real SQL

    /** productId, quantity, unit price. */
    private record Line(String productId, int quantity, int price) {
    }

    private static final Instant T = Instant.parse("2026-03-01T10:00:00Z");

    private void listed(String id, String userId, String status, Instant createdAt, String customerName, Consumer<Order> tweak, Line... lines) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Order order = new Order(userId, null, null, customerName, "010-1234-5678", "서울시 어딘가 1", 1000, 0);
            ReflectionTestUtils.setField(order, "id", id);
            ReflectionTestUtils.setField(order, "createdAt", createdAt);
            ReflectionTestUtils.setField(order, "status", status);
            if ("CANCELING".equals(status)) {
                ReflectionTestUtils.setField(order, "cancelFrom", "PAID"); // orders_canceling_has_origin
            }
            for (Line line : lines) {
                order.addItem(new OrderItem(em.getReference(Product.class, line.productId()), line.quantity(), line.price()));
            }
            tweak.accept(order);
            em.persist(order);
        });
    }

    private void listed(String id, String userId, String status, Instant createdAt) {
        listed(id, userId, status, createdAt, "구매자", o -> {
        }, new Line("lp1", 1, 1000));
    }

    private static String oid(int n) {
        return String.format("%08d", n); // digits only: the same order in every collation
    }

    private void listProducts() {
        data.product("lp1", null, 1000, null, TestData.T0);
        data.product("lp2", null, 3000, null, TestData.T0);
    }

    private List<String> ids(OrderPage page) {
        return page.orders().stream().map(o -> o.order().getId()).toList();
    }

    /** Follows nextCursor to the end; returns every id in the served order and records the page sizes. */
    private List<String> pageThrough(String status, int size, List<Integer> pageSizes) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            OrderPage page = orderService.listAdminOrders(status, size, cursor);
            pageSizes.add(page.orders().size());
            ids.addAll(ids(page));
            cursor = page.nextCursor();
            assertThat(++guard).as("the cursor must advance, never loop").isLessThan(200);
        } while (cursor != null);
        return ids;
    }

    @Test
    void 성공_관리자_목록은_모든_사용자의_주문을_최신순으로_돌려준다() {
        listProducts();
        String userA = data.user();
        String userB = data.user();
        listed(oid(1), userA, "PAID", T);
        listed(oid(2), userB, "SHIPPED", T.plusSeconds(30));
        listed(oid(3), userA, "CANCELLED", T.plusSeconds(10));
        listed(oid(4), userB, "PENDING_PAYMENT", T.plusSeconds(20));

        OrderPage page = orderService.listAdminOrders(null, 50, null);

        assertThat(ids(page)).containsExactly(oid(2), oid(4), oid(3), oid(1));
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void 성공_상태_필터는_그_상태의_주문만_돌려주고_아홉_가지_상태가_모두_걸러진다() {
        listProducts();
        List<String> statuses = Order.STATUSES;
        for (int i = 0; i < statuses.size(); i++) {
            listed(oid(i * 2), null, statuses.get(i), T.plusSeconds(i));
            listed(oid(i * 2 + 1), null, statuses.get(i), T.plusSeconds(100 + i));
        }

        for (int i = 0; i < statuses.size(); i++) {
            String wanted = statuses.get(i);
            OrderPage page = orderService.listAdminOrders(wanted, 50, null);
            assertThat(ids(page)).as(wanted).containsExactly(oid(i * 2 + 1), oid(i * 2));
            assertThat(page.orders()).allSatisfy(o -> assertThat(o.order().getStatus()).isEqualTo(wanted));
        }
    }

    @Test
    void 성공_상태_필터_결과의_모든_행이_그_상태이다() {
        listProducts();
        for (int i = 0; i < 12; i++) {
            listed(oid(i), null, i % 3 == 0 ? "PAID" : i % 3 == 1 ? "SHIPPED" : "REFUNDED", T.plusSeconds(i));
        }

        OrderPage page = orderService.listAdminOrders("SHIPPED", 50, null);

        assertThat(page.orders()).hasSize(4).allSatisfy(o -> assertThat(o.order().getStatus()).isEqualTo("SHIPPED"));
    }

    @Test
    void 성공_상태가_null_빈_문자열_공백이면_모든_상태를_돌려준다() {
        listProducts();
        listed(oid(1), null, "PAID", T);
        listed(oid(2), null, "CANCELLED", T.plusSeconds(1));
        listed(oid(3), null, "REFUNDED", T.plusSeconds(2));

        assertThat(ids(orderService.listAdminOrders(null, 50, null))).hasSize(3);
        assertThat(ids(orderService.listAdminOrders("", 50, null))).hasSize(3);
        assertThat(ids(orderService.listAdminOrders("   ", 50, null))).hasSize(3);
    }

    @Test
    void 실패_알_수_없는_상태_소문자_앞뒤_공백은_ValidationException이다() {
        listProducts();
        listed(oid(1), null, "PAID", T);

        for (String status : new String[]{"UNKNOWN", "paid", "Paid", "PAID ", " PAID", "CANCELED", "ALL", "PAID,SHIPPED", "PAID' OR '1'='1"}) {
            assertThatThrownBy(() -> orderService.listAdminOrders(status, 50, null)).as("[%s]", status).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void 성공_빈_목록은_빈_페이지이고_nextCursor가_없다() {
        OrderPage page = orderService.listAdminOrders(null, 50, null);

        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
        assertThat(orderService.listAdminOrders("PAID", 1, null).orders()).isEmpty();
    }

    @Test
    void 실패_size가_1보다_작거나_50보다_크면_ValidationException이고_1과_50은_받는다() {
        listProducts();
        for (int i = 0; i < 52; i++) {
            listed(oid(i), null, "PAID", T.plusSeconds(i));
        }

        for (int size : new int[]{0, -1, 51, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> orderService.listAdminOrders(null, size, null)).as("size %d", size).isInstanceOf(ValidationException.class);
        }

        OrderPage one = orderService.listAdminOrders(null, 1, null);
        assertThat(one.orders()).hasSize(1);
        assertThat(one.nextCursor()).isNotNull();
        OrderPage fifty = orderService.listAdminOrders(null, 50, null);
        assertThat(fifty.orders()).hasSize(50);
        assertThat(fifty.nextCursor()).isNotNull();
        assertThat(orderService.listAdminOrders(null, 50, fifty.nextCursor()).orders()).hasSize(2);
    }

    @Test
    void 실패_깨진_커서는_ValidationException이다() {
        listProducts();
        listed(oid(1), null, "PAID", T);

        for (String cursor : new String[]{"", "!!!", "bm90LWEtY3Vyc29y", "x".repeat(300)}) {
            assertThatThrownBy(() -> orderService.listAdminOrders(null, 10, cursor)).as("[%s]", cursor).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void 성공_같은_created_at이_많아도_키셋_페이지는_모든_주문을_정확히_한_번씩_방문한다() {
        listProducts();
        Instant[] times = {T, T.plusSeconds(60).plusNanos(123_456_000), T.plusSeconds(120)};
        List<String> all = new ArrayList<>();
        List<Instant> createdAt = new ArrayList<>();
        // 24 orders on 3 timestamps (8 each); ids interleaved so that insertion order is unrelated to the (created_at, id) order
        for (int i = 0; i < 24; i++) {
            Instant t = times[(i * 7) % 3];
            listed(oid(i * 5 % 24), null, i % 2 == 0 ? "PAID" : "SHIPPED", t);
            all.add(oid(i * 5 % 24));
            createdAt.add(t);
        }
        List<String> expected = new ArrayList<>(all);
        expected.sort(Comparator.comparing((String id) -> createdAt.get(all.indexOf(id))).thenComparing(Comparator.<String>naturalOrder()).reversed());
        assertThat(all).doesNotHaveDuplicates().hasSize(24);

        List<Integer> pageSizes = new ArrayList<>();
        List<String> served = pageThrough(null, 5, pageSizes);

        assertThat(served).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
        assertThat(pageSizes).containsExactly(5, 5, 5, 5, 4);
    }

    @Test
    void 성공_모든_주문의_created_at이_같아도_size_1로_끝까지_넘기면_빠짐없이_한_번씩이다() {
        listProducts();
        for (int i = 0; i < 9; i++) {
            listed(oid(i), null, Order.STATUSES.get(i), T);
        }

        List<Integer> pageSizes = new ArrayList<>();
        List<String> served = pageThrough(null, 1, pageSizes);

        assertThat(served).containsExactly(oid(8), oid(7), oid(6), oid(5), oid(4), oid(3), oid(2), oid(1), oid(0));
        assertThat(pageSizes).hasSize(9).containsOnly(1);
    }

    @Test
    void 성공_상태_필터와_같은_created_at_타이가_함께_있어도_키셋_페이지가_빠짐없이_한_번씩이다() {
        listProducts();
        for (int i = 0; i < 20; i++) {
            listed(oid(i), null, i % 2 == 0 ? "PAID" : "CANCELLED", i < 10 ? T : T.plusSeconds(5));
        }

        List<Integer> pageSizes = new ArrayList<>();
        List<String> served = pageThrough("PAID", 3, pageSizes);

        // PAID = even ids; the later timestamp (ids 10..19) comes first, ids descending inside a tie
        assertThat(served).containsExactly(oid(18), oid(16), oid(14), oid(12), oid(10), oid(8), oid(6), oid(4), oid(2), oid(0));
        assertThat(pageSizes).containsExactly(3, 3, 3, 1);
    }

    @Test
    void 성공_정확히_size개만_남은_마지막_페이지는_nextCursor가_없다() {
        listProducts();
        for (int i = 0; i < 6; i++) {
            listed(oid(i), null, "PAID", T.plusSeconds(i));
        }

        OrderPage first = orderService.listAdminOrders(null, 3, null);
        OrderPage second = orderService.listAdminOrders(null, 3, first.nextCursor());

        assertThat(first.nextCursor()).isNotNull();
        assertThat(ids(second)).containsExactly(oid(2), oid(1), oid(0));
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void 성공_목록_항목에_주문자_이름_연락처_주소_금액_상태_상품_요약과_시각이_담긴다() {
        listProducts();
        String user = data.user();
        Instant paidAt = T.plusSeconds(5);
        Instant shippedAt = T.plusSeconds(3600);
        listed(oid(1), user, "SHIPPED", T, "홍길동", o -> {
            ReflectionTestUtils.setField(o, "paidAt", paidAt);
            ReflectionTestUtils.setField(o, "shippedAt", shippedAt);
        }, new Line("lp1", 1, 1000), new Line("lp2", 2, 3000));

        OrderService.OrderSummary summary = orderService.listAdminOrders(null, 10, null).orders().get(0);

        Order order = summary.order();
        assertThat(order.getId()).isEqualTo(oid(1));
        assertThat(order.getUserId()).isEqualTo(user);
        assertThat(order.getCustomerName()).isEqualTo("홍길동");
        assertThat(order.getCustomerPhone()).isEqualTo("010-1234-5678");
        assertThat(order.getCustomerAddress()).isEqualTo("서울시 어딘가 1");
        assertThat(order.getTotalAmount()).isEqualTo(1000);
        assertThat(order.getStatus()).isEqualTo("SHIPPED");
        assertThat(order.getCreatedAt()).isEqualTo(T);
        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThat(order.getShippedAt()).isEqualTo(shippedAt);
        assertThat(order.getDeliveredAt()).isNull();
        assertThat(summary.itemCount()).isEqualTo(2);
        assertThat(summary.firstItemName()).as("the line with the biggest amount represents the order").isEqualTo("상품 lp2");
    }

    @Test
    void 성공_주인이_없는_기존_주문과_품목이_없는_주문도_목록에_나오고_커서도_넘어간다() {
        listProducts();
        String user = data.user();
        listed(oid(1), null, "PAID", T);                       // legacy: user_id NULL
        listed(oid(2), null, "PAID", T, "구매자", o -> {
        });                                                     // no lines at all
        listed(oid(3), user, "PAID", T.plusSeconds(1));
        listed(oid(4), null, "REFUNDED", T.plusSeconds(2));

        OrderPage all = orderService.listAdminOrders(null, 2, null);
        OrderPage rest = orderService.listAdminOrders(null, 2, all.nextCursor());

        assertThat(ids(all)).containsExactly(oid(4), oid(3));
        assertThat(ids(rest)).containsExactly(oid(2), oid(1));
        assertThat(rest.orders().get(0).itemCount()).isZero();
        assertThat(rest.orders().get(0).firstItemName()).isNull();
        assertThat(rest.orders().get(1).order().getUserId()).isNull();
        assertThat(ids(orderService.listAdminOrders("REFUNDED", 10, null))).containsExactly(oid(4));
    }

    @Test
    void 성공_실제_흐름으로_바뀐_상태가_관리자_목록과_상태_필터에_바로_보인다() {
        product("p1", 20);
        Placed cancelled = paid(data.user(), line("p1", 1));
        Placed shipped = shipped(data.user(), line("p1", 1));
        Placed refunded = shipped(data.user(), line("p1", 1));
        Placed canceling = paid(data.user(), line("p1", 1));
        Placed plainPaid = paid(data.user(), line("p1", 1));
        cancelService.cancelByCustomer(cancelled.orderId(), cancelled.userId(), "사유");
        cancelService.refundByAdmin(refunded.orderId(), "하자");
        customerCancelInFlight(canceling, "사유");

        assertThat(ids(orderService.listAdminOrders("CANCELLED", 50, null))).containsExactly(cancelled.orderId());
        assertThat(ids(orderService.listAdminOrders("SHIPPED", 50, null))).containsExactly(shipped.orderId());
        assertThat(ids(orderService.listAdminOrders("REFUNDED", 50, null))).containsExactly(refunded.orderId());
        assertThat(ids(orderService.listAdminOrders("CANCELING", 50, null))).containsExactly(canceling.orderId());
        assertThat(ids(orderService.listAdminOrders("PAID", 50, null))).containsExactly(plainPaid.orderId());
    }

    // ------------------------------------------------------------------ 9. sales ranking counts only PAID / SHIPPED / DELIVERED

    private static final Instant SALE_AT = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SINCE = Instant.parse("2026-01-01T00:00:00Z");

    private List<String> ids(List<Product> products) {
        return products.stream().map(Product::getId).toList();
    }

    @Autowired
    private ProductRepository products;

    @Test
    void 성공_판매_순위와_베스트는_PAID_SHIPPED_DELIVERED만_센다() {
        // q1..q9 get one order each in the status of Order.STATUSES[i-1]; q0 has a PAID order of 1 unit
        data.product("q0", null, 1000, null, TestData.T0);
        data.sale("q0", "PAID", 1, SALE_AT);
        for (int i = 0; i < 9; i++) {
            data.product("q" + (i + 1), null, 1000, null, TestData.T0);
            data.sale("q" + (i + 1), Order.STATUSES.get(i), 5, SALE_AT);
        }
        // STATUSES: q1 PENDING_PAYMENT, q2 PAID, q3 EXPIRED, q4 PAYMENT_REVIEW, q5 CANCELING, q6 CANCELLED, q7 SHIPPED, q8 DELIVERED, q9 REFUNDED

        List<String> bySales = ids(products.findBySalesDesc(null, PageRequest.of(0, 20)).getContent());
        List<String> best = ids(products.findBestSoldSince(SINCE, PageRequest.of(0, 20)));
        List<String> popular = ids(products.findByPopularDesc(null, PageRequest.of(0, 20)).getContent());

        // 5 units: q8 DELIVERED, q7 SHIPPED, q2 PAID (ties by id descending); then q0 with 1 unit; the other statuses count 0
        assertThat(bySales).containsExactly("q8", "q7", "q2", "q0", "q9", "q6", "q5", "q4", "q3", "q1");
        assertThat(best).containsExactly("q8", "q7", "q2", "q0");
        assertThat(popular.subList(0, 4)).containsExactly("q8", "q7", "q2", "q0");
    }

    @Test
    void 성공_한_상품의_판매_수량은_PAID_SHIPPED_DELIVERED_주문만_합산한다() {
        data.product("px", null, 1000, null, TestData.T0);
        data.product("py", null, 1000, null, TestData.T0);
        data.product("pw", null, 1000, null, TestData.T0);
        // px: sold 1 + 2 + 4 = 7; every other status carries a huge quantity that would win if it were counted
        data.sale("px", "PAID", 1, SALE_AT);
        data.sale("px", "SHIPPED", 2, SALE_AT);
        data.sale("px", "DELIVERED", 4, SALE_AT);
        for (String status : List.of("PENDING_PAYMENT", "EXPIRED", "PAYMENT_REVIEW", "CANCELING", "CANCELLED", "REFUNDED")) {
            data.sale("px", status, 100, SALE_AT);
        }
        data.sale("py", "PAID", 8, SALE_AT);
        data.sale("pw", "PAID", 6, SALE_AT);

        assertThat(ids(products.findBySalesDesc(null, PageRequest.of(0, 10)).getContent())).containsExactly("py", "px", "pw");
        assertThat(ids(products.findBestSoldSince(SINCE, PageRequest.of(0, 10)))).containsExactly("py", "px", "pw");
    }

    @Test
    void 성공_주문이_취소_환불되면_베스트에서_빠지고_배송_중_배송_완료는_그대로_센다() {
        for (String id : List.of("pA", "pB", "pC", "pD", "pE", "pF")) {
            product(id, 10);
        }
        Placed a = paid(data.user(), line("pA", 1));
        shipped(data.user(), line("pB", 1));
        Placed c = shipped(data.user(), line("pC", 1));
        Placed d = paid(data.user(), line("pD", 1));
        paid(data.user(), line("pE", 1));
        delivered(data.user(), line("pF", 1));
        // these orders are created "now" by the service, so look back far enough
        Instant longAgo = Instant.parse("2000-01-01T00:00:00Z");
        assertThat(ids(products.findBestSoldSince(longAgo, PageRequest.of(0, 20))))
                .as("all six count while they are paid / shipped / delivered").containsExactlyInAnyOrder("pA", "pB", "pC", "pD", "pE", "pF");

        cancelService.cancelByCustomer(a.orderId(), a.userId(), "사유");   // CANCELLED
        cancelService.refundByAdmin(c.orderId(), "하자");                  // REFUNDED
        customerCancelInFlight(d, "사유");                                  // CANCELING

        List<String> after = ids(products.findBestSoldSince(longAgo, PageRequest.of(0, 20)));
        assertThat(after).containsExactlyInAnyOrder("pB", "pE", "pF");
        assertThat(ids(products.findBySalesDesc(null, PageRequest.of(0, 20)).getContent()).subList(0, 3))
                .containsExactlyInAnyOrder("pB", "pE", "pF");
    }
}
