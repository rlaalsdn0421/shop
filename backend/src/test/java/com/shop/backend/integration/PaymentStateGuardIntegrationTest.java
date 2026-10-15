package com.shop.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.application.service.PaymentGateway.Payment;
import com.shop.backend.application.service.PaymentGateway.Requester;
import com.shop.backend.application.service.PaymentService;
import com.shop.backend.application.service.PaymentTransactions;
import com.shop.backend.application.service.WebhookSignatureVerifier;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.OrderStateException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * "Payment confirmation never moves a shipped / cancelled / refunded order back to PAID" on a real Postgres: the
 * early return in PaymentService.complete (no provider call), the guard in PaymentTransactions.applyPaid (stale read),
 * Order.markPaid itself, the V16 CANCELING origin constraint, and the cancel/refund outcome semantics. Only the
 * provider is faked: {@link StubGateway} always answers PAID/LIVE with the right amount (so a missing guard is caught)
 * and records every find() and cancel(). The clock is the fixed one of IntegrationTestConfig.
 *
 * <p>Every test starts from products p1, p2 (the order's lines) and p3 (a bystander) with stock 10 each.
 */
@TestPropertySource(properties = {
        "portone.store-id=" + PaymentStateGuardIntegrationTest.STORE_ID,
        "portone.webhook-secret=" + PaymentStateGuardIntegrationTest.WEBHOOK_SECRET,
        "portone.allow-test-channel=false"})
class PaymentStateGuardIntegrationTest extends PostgresIntegrationTest {

    static final String STORE_ID = "store-test-0001";
    // Made-up key, base64 of "integration-test-webhook-key-0123" (not a real secret)
    static final String WEBHOOK_SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC13ZWJob29rLWtleS0wMTIz";
    private static final byte[] WEBHOOK_KEY = "integration-test-webhook-key-0123".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = IntegrationTestConfig.FIXED_NOW;

    /** Stock before any order (p1, p2, p3) and after the standard order (p1 x3, p2 x2) reserved its share. */
    private static final Map<String, Integer> INITIAL = Map.of("p1", 10, "p2", 10, "p3", 10);
    private static final Map<String, Integer> HELD = Map.of("p1", 7, "p2", 8, "p3", 10);

    private static final String[] PAST_PAYMENT = {"CANCELING_FROM_PAID", "CANCELING_FROM_SHIPPED",
            "CANCELING_FROM_DELIVERED", "CANCELLED", "SHIPPED", "DELIVERED", "REFUNDED"};

    /** How the fake provider answers cancel() for one payment id (default OK). */
    enum CancelMode {
        /** Cancelled: returns normally (the payment turns CANCELLED at the provider). */
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

        void reset() {
            payments.clear();
            cancelModes.clear();
            cancelCalls.clear();
            findCalls.set(0);
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public Optional<Payment> find(String paymentId) {
            findCalls.incrementAndGet();
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
    static class GuardTestConfig {
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
    private OrderRepository orderRepository;
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

    private void seedProducts() {
        product("p1", 10);
        product("p2", 10);
        product("p3", 10);
    }

    /** The stock of EVERY product. */
    private Map<String, Integer> allStocks() {
        Map<String, Integer> result = new TreeMap<>();
        jdbc.query("SELECT id, stock FROM products", rs -> {
            result.put(rs.getString(1), rs.getInt(2));
        });
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

    private Instant instantOf(String column, String orderId) {
        Timestamp ts = jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", Timestamp.class, orderId);
        return ts == null ? null : ts.toInstant();
    }

    private Placed place(String userId, OrderLine... lines) {
        Order order = orderService.createOrder(userId, "key-" + UUID.randomUUID(), "구매자", "010-0000-0000", "서울", List.of(lines));
        return new Placed(userId, order.getId(), order.getTotalAmount());
    }

    /** The provider says PAID / LIVE / the exact amount: what a missing guard would happily accept. */
    private void providerPaid(Placed p) {
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "PAID", (long) p.total(), "KRW",
                "tx-" + p.orderId().substring(0, 8), "LIVE"));
    }

    /** Placed, confirmed with the provider and the stub's counters cleared. */
    private Placed paid(String userId, OrderLine... lines) {
        Placed p = place(userId, lines);
        providerPaid(p);
        assertThat(paymentService.complete(p.orderId(), userId)).isEqualTo("PAID");
        gateway.findCalls.set(0);
        return p;
    }

    private Placed standardPaid() {
        return paid(data.user(), line("p1", 3), line("p2", 2));
    }

    private Placed shipped() {
        Placed p = standardPaid();
        assertThat(orderService.shipOrder(p.orderId())).isEqualTo("SHIPPED");
        return p;
    }

    private Placed delivered() {
        Placed p = shipped();
        assertThat(orderService.deliverOrder(p.orderId())).isEqualTo("DELIVERED");
        return p;
    }

    /** Customer cancel whose provider call timed out without effect: the order is left CANCELING (cancel_from PAID). */
    private void customerCancelInFlight(Placed p) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
    }

    /** Admin refund whose provider call timed out without effect: the order is left CANCELING (cancel_from SHIPPED/DELIVERED). */
    private void refundInFlight(Placed p) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "사유"))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        assertThat(status(p.orderId())).isEqualTo("CANCELING");
    }

    /**
     * Drives a fresh standard order (p1 x3, p2 x2) through the REAL services into the given state, then makes the
     * provider answer PAID again and clears all counters, so nothing but a missing guard can change the order.
     */
    private Placed reach(String scenario) {
        Placed p;
        switch (scenario) {
            case "CANCELING_FROM_PAID" -> {
                p = standardPaid();
                customerCancelInFlight(p);
            }
            case "CANCELING_FROM_SHIPPED" -> {
                p = shipped();
                refundInFlight(p);
            }
            case "CANCELING_FROM_DELIVERED" -> {
                p = delivered();
                refundInFlight(p);
            }
            case "CANCELLED" -> {
                p = standardPaid();
                assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");
            }
            case "SHIPPED" -> p = shipped();
            case "DELIVERED" -> p = delivered();
            case "REFUNDED" -> {
                p = shipped();
                assertThat(cancelService.refundByAdmin(p.orderId(), "사유")).isEqualTo("REFUNDED");
            }
            default -> throw new IllegalArgumentException(scenario);
        }
        providerPaid(p);
        gateway.findCalls.set(0);
        gateway.cancelCalls.clear();
        return p;
    }

    private static String expectedStatus(String scenario) {
        return scenario.startsWith("CANCELING") ? "CANCELING" : scenario;
    }

    // cancel_from is kept for the record after the cancel finishes
    private static String expectedCancelFrom(String scenario) {
        return switch (scenario) {
            case "CANCELLED" -> "PAID";
            case "REFUNDED" -> "SHIPPED";
            default -> scenario.startsWith("CANCELING_FROM_") ? scenario.substring("CANCELING_FROM_".length()) : null;
        };
    }

    /** EXPIRED: overdue and swept by the real expiry job (stock given back). */
    private Placed expired(OrderLine... lines) {
        Placed p = place(data.user(), lines);
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?", OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), p.orderId());
        assertThat(paymentService.expirePending()).isEqualTo(1);
        return p;
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private void webhookPaid(String paymentId) throws Exception {
        String body = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"" + STORE_ID + "\",\"transactionId\":\"tx-1\"}}";
        String id = "msg_" + UUID.randomUUID();
        String ts = String.valueOf(NOW.getEpochSecond());
        paymentService.handleWebhook(id, ts, sign(id, ts, body), body);
    }

    private void assertCode(Throwable t, Class<? extends OrderStateException> type, String code) {
        assertThat(t).isInstanceOfSatisfying(type, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    /** Nothing at all changed: the order row, EVERY product stock, no provider lookup, no provider cancel. */
    private void assertUntouched(Placed p, Map<String, Object> rowBefore, Map<String, Integer> stockBefore, String context) {
        assertThat(row(p.orderId())).as("%s: order row", context).isEqualTo(rowBefore);
        assertThat(allStocks()).as("%s: every product stock", context).isEqualTo(stockBefore);
        assertThat(gateway.findCalls.get()).as("%s: provider lookups", context).isZero();
        assertThat(gateway.cancelCalls).as("%s: provider cancels", context).isEmpty();
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
     * queued on that lock, then releases it. Every task must take the row lock (no read-only short circuit).
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

    private void newRound() {
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        gateway.reset();
        seedProducts();
    }

    private static boolean failed(Object result) {
        return result instanceof Throwable;
    }

    private void insertRaw(String status, String cancelFrom) {
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at, "
                        + "cancel_from) VALUES (?, 'n', 'p', 'a', 1, ?, ?, ?)",
                UUID.randomUUID().toString(), status, Timestamp.valueOf(LocalDateTime.now()), cancelFrom);
    }

    private String insertRawPaid() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                + "VALUES (?, 'n', 'p', 'a', 1, 'PAID', ?)", id, Timestamp.valueOf(LocalDateTime.now()));
        return id;
    }

    // ------------------------------------------------------------------ 1. complete / webhook never touch a past-payment order

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING_FROM_PAID", "CANCELING_FROM_SHIPPED", "CANCELING_FROM_DELIVERED", "CANCELLED",
            "SHIPPED", "DELIVERED", "REFUNDED"})
    void 성공_결제_확정_API는_배송_취소_이후_상태를_그대로_돌려주고_포트원을_부르지_않으며_아무것도_바꾸지_않는다(String scenario) {
        seedProducts();
        Placed p = reach(scenario);
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();
        assertThat(rowBefore).containsEntry("status", expectedStatus(scenario)).containsEntry("cancel_from", expectedCancelFrom(scenario));
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);

        assertThat(paymentService.complete(p.orderId(), p.userId())).as(scenario + " owner").isEqualTo(expectedStatus(scenario));
        assertThat(paymentService.complete(p.orderId(), p.userId())).as(scenario + " owner retry").isEqualTo(expectedStatus(scenario));
        assertThat(paymentService.complete(p.orderId(), null)).as(scenario + " webhook path").isEqualTo(expectedStatus(scenario));

        assertUntouched(p, rowBefore, stockBefore, scenario);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING_FROM_PAID", "CANCELING_FROM_SHIPPED", "CANCELING_FROM_DELIVERED", "CANCELLED",
            "SHIPPED", "DELIVERED", "REFUNDED"})
    void 성공_서명된_결제_완료_웹훅은_재전송을_포함해_배송_취소_이후_주문을_바꾸지_않고_포트원도_부르지_않는다(String scenario) throws Exception {
        seedProducts();
        Placed p = reach(scenario);
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();

        webhookPaid(p.orderId());
        webhookPaid(p.orderId()); // the provider retries
        webhookPaid(p.orderId());

        assertUntouched(p, rowBefore, stockBefore, scenario);
        assertThat(status(p.orderId())).isEqualTo(expectedStatus(scenario));
    }

    @Test
    void 실패_다른_사용자의_결제_확정은_배송된_주문에서도_404이고_상태를_알려주지_않는다() {
        seedProducts();
        Placed p = reach("SHIPPED");
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();

        assertThatThrownBy(() -> paymentService.complete(p.orderId(), data.user())).isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> paymentService.complete(p.orderId(), "")).isInstanceOf(OrderNotFoundException.class);

        assertUntouched(p, rowBefore, stockBefore, "foreign user");
    }

    // ------------------------------------------------------------------ 2. the original exploit, end to end

    @Test
    void 실패_결제_확정으로_배송된_주문을_PAID로_되돌린_뒤_전액_취소하는_우회는_막힌다() throws Exception {
        seedProducts();
        Placed p = standardPaid();
        assertThat(allStocks()).isEqualTo(HELD);
        assertThat(orderService.shipOrder(p.orderId())).isEqualTo("SHIPPED");
        Map<String, Object> shippedRow = row(p.orderId());
        providerPaid(p); // the provider keeps reporting PAID for a shipped order

        // the browser's confirm call and the webhook
        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("SHIPPED");
        webhookPaid(p.orderId());
        assertThat(status(p.orderId())).isEqualTo("SHIPPED");

        // the owner now tries to cancel for a full refund
        Throwable thrown = catchThrowable(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "환불해 주세요"));

        assertCode(thrown, OrderNotCancellableException.class, "ORDER_NOT_CANCELLABLE");
        assertThat(row(p.orderId())).isEqualTo(shippedRow);
        assertThat(status(p.orderId())).isEqualTo("SHIPPED");
        assertThat(instantOf("shipped_at", p.orderId())).isNotNull();
        assertThat(allStocks()).as("goods are gone, stock must not come back").isEqualTo(HELD);
        assertThat(gateway.cancelCalls).as("the provider is never asked to refund").isEmpty();
    }

    @Test
    void 실패_배송완료_주문도_같은_우회로_취소할_수_없다() throws Exception {
        seedProducts();
        Placed p = reach("DELIVERED");
        Map<String, Object> deliveredRow = row(p.orderId());

        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("DELIVERED");
        webhookPaid(p.orderId());
        Throwable thrown = catchThrowable(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"));

        assertCode(thrown, OrderNotCancellableException.class, "ORDER_NOT_CANCELLABLE");
        assertThat(row(p.orderId())).isEqualTo(deliveredRow);
        assertThat(allStocks()).isEqualTo(HELD);
        assertThat(gateway.cancelCalls).isEmpty();
    }

    // ------------------------------------------------------------------ 3. refund in flight

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING_FROM_SHIPPED", "CANCELING_FROM_DELIVERED"})
    void 성공_진행중인_환불은_결제_확정과_웹훅에도_CANCELING이고_관리자가_이어받으면_재고_복구_없이_REFUNDED가_된다(String scenario) throws Exception {
        seedProducts();
        Placed p = reach(scenario);
        Map<String, Object> inFlight = row(p.orderId());

        assertThat(paymentService.complete(p.orderId(), p.userId())).isEqualTo("CANCELING");
        webhookPaid(p.orderId());
        assertThat(row(p.orderId())).isEqualTo(inFlight);
        assertThat(allStocks()).isEqualTo(HELD);
        assertThat(gateway.findCalls.get()).isZero();

        assertThat(cancelService.refundByAdmin(p.orderId(), "재개")).isEqualTo("REFUNDED");

        assertThat(status(p.orderId())).isEqualTo("REFUNDED");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(allStocks()).as("returned goods are inspected first: no automatic restore").isEqualTo(HELD);
        assertThat(gateway.cancelCalls).hasSize(1);
        assertThat(gateway.cancelCalls.get(0).requester()).isEqualTo(Requester.ADMIN);
    }

    // ------------------------------------------------------------------ 4. applyPaid directly (the stale read)

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING_FROM_PAID", "CANCELING_FROM_SHIPPED", "CANCELING_FROM_DELIVERED", "CANCELLED",
            "SHIPPED", "DELIVERED", "REFUNDED"})
    void 성공_applyPaid가_오래된_읽기로_직접_불려도_배송_취소_이후_주문은_그대로이고_현재_상태를_돌려준다(String scenario) {
        seedProducts();
        Placed p = reach(scenario);
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();

        String result = transactions.applyPaid(p.orderId(), "tx-stale-replacement");

        assertThat(result).isEqualTo(expectedStatus(scenario));
        assertUntouched(p, rowBefore, stockBefore, scenario);
        assertThat(row(p.orderId())).containsEntry("status", expectedStatus(scenario));
        assertThat(row(p.orderId()).get("payment_transaction_id")).isNotEqualTo("tx-stale-replacement");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELING_FROM_PAID", "CANCELING_FROM_SHIPPED", "CANCELING_FROM_DELIVERED", "CANCELLED",
            "SHIPPED", "DELIVERED", "REFUNDED"})
    void 실패_Order_markPaid는_배송_취소_이후_상태에서_InvalidOrderStateException이고_행은_그대로다(String scenario) {
        seedProducts();
        Placed p = reach(scenario);
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();

        Throwable thrown = catchThrowable(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Order order = orderRepository.findByIdForUpdate(p.orderId()).orElseThrow();
            order.markPaid(NOW.plusSeconds(5), "tx-direct-markpaid");
        }));

        assertCode(thrown, InvalidOrderStateException.class, "INVALID_ORDER_STATE");
        assertUntouched(p, rowBefore, stockBefore, scenario);
    }

    @Test
    void 성공_applyPaid는_PENDING_PAYMENT를_PAID로_만들고_재고는_이미_잡혀_있으니_다시_빼지_않는다() {
        seedProducts();
        Placed p = place(data.user(), line("p1", 3), line("p2", 2));
        assertThat(allStocks()).isEqualTo(HELD);

        assertThat(transactions.applyPaid(p.orderId(), "tx-direct")).isEqualTo("PAID");

        assertThat(row(p.orderId())).containsEntry("status", "PAID").containsEntry("payment_transaction_id", "tx-direct");
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(allStocks()).isEqualTo(HELD);
    }

    @Test
    void 성공_applyPaid는_만료된_주문을_재고가_있으면_다시_잡고_PAID로_만든다() {
        seedProducts();
        Placed p = expired(line("p1", 3), line("p2", 2));
        assertThat(status(p.orderId())).isEqualTo("EXPIRED");
        assertThat(allStocks()).as("expiry gave the stock back").isEqualTo(INITIAL);

        assertThat(transactions.applyPaid(p.orderId(), "tx-late")).isEqualTo("PAID");

        assertThat(row(p.orderId())).containsEntry("status", "PAID").containsEntry("payment_transaction_id", "tx-late");
        assertThat(instantOf("paid_at", p.orderId())).isEqualTo(NOW);
        assertThat(allStocks()).as("reserved again").isEqualTo(HELD);
    }

    @Test
    void 실패_applyPaid는_만료된_주문의_재고가_모자라면_PAYMENT_REVIEW로_두고_잡았던_재고를_돌려놓는다() {
        seedProducts();
        Placed p = expired(line("p1", 3), line("p2", 2));
        jdbc.update("UPDATE products SET stock = 1 WHERE id = 'p2'"); // p1 can be reserved, p2 cannot
        Map<String, Integer> stockBefore = allStocks();

        assertThat(transactions.applyPaid(p.orderId(), "tx-late")).isEqualTo("PAYMENT_REVIEW");

        assertThat(row(p.orderId())).containsEntry("status", "PAYMENT_REVIEW");
        assertThat(instantOf("paid_at", p.orderId())).isNull();
        assertThat(allStocks()).as("the partial reservation was given back").isEqualTo(stockBefore);
    }

    @Test
    void 실패_PAYMENT_REVIEW_주문은_applyPaid가_다시_와도_PAID가_되지_않고_그대로다() {
        seedProducts();
        Placed p = expired(line("p1", 3), line("p2", 2));
        jdbc.update("UPDATE products SET stock = 1 WHERE id = 'p2'");
        assertThat(transactions.applyPaid(p.orderId(), "tx-late")).isEqualTo("PAYMENT_REVIEW");
        Map<String, Object> rowBefore = row(p.orderId());
        jdbc.update("UPDATE products SET stock = 10"); // even with stock available again: an operator decides
        Map<String, Integer> stockBefore = allStocks();

        assertThatThrownBy(() -> transactions.applyPaid(p.orderId(), "tx-again")).isInstanceOf(PaymentNeedsReviewException.class);

        assertThat(row(p.orderId())).isEqualTo(rowBefore);
        assertThat(allStocks()).isEqualTo(stockBefore);
    }

    @Test
    void 성공_PAID_주문에_applyPaid가_다시_와도_멱등이다() {
        seedProducts();
        Placed p = standardPaid();
        Map<String, Object> rowBefore = row(p.orderId());

        assertThat(transactions.applyPaid(p.orderId(), "tx-other")).isEqualTo("PAID");
        assertThat(transactions.applyPaid(p.orderId(), "tx-other")).isEqualTo("PAID");

        assertThat(row(p.orderId())).isEqualTo(rowBefore);
        assertThat(row(p.orderId()).get("payment_transaction_id")).isNotEqualTo("tx-other");
        assertThat(allStocks()).isEqualTo(HELD);
    }

    // ------------------------------------------------------------------ 5. races with real threads

    @Test
    void 성공_배송_시작과_결제_확정_요청_세_가지가_동시에_와도_SHIPPED로_끝나고_재고와_결제_정보는_그대로다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            Placed p = standardPaid();
            Map<String, Object> paidRow = row(p.orderId());

            List<Object> results = runTogether(List.of(
                    () -> orderService.shipOrder(p.orderId()),
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> paymentService.complete(p.orderId(), null),
                    () -> transactions.applyPaid(p.orderId(), "tx-stale")));

            String context = "round " + round;
            assertThat(results.stream().filter(PaymentStateGuardIntegrationTest::failed).toList()).as(context).isEmpty();
            assertThat(results.get(0)).as(context).isEqualTo("SHIPPED");
            assertThat(status(p.orderId())).as("%s: a late payment confirmation must not undo the shipment", context).isEqualTo("SHIPPED");
            Map<String, Object> after = row(p.orderId());
            assertThat(after.get("paid_at")).as(context).isEqualTo(paidRow.get("paid_at"));
            assertThat(after.get("payment_transaction_id")).as(context).isEqualTo(paidRow.get("payment_transaction_id"));
            assertThat(after.get("shipped_at")).as(context).isNotNull();
            assertThat(after.get("cancel_from")).as(context).isNull();
            assertThat(allStocks()).as("%s: SHIPPED => stock unchanged", context).isEqualTo(HELD);
            assertThat(gateway.cancelCalls).as(context).isEmpty();
        }
    }

    @Test
    void 성공_고객_취소와_결제_확정_요청_세_가지가_동시에_와도_CANCELLED로_끝나고_재고는_정확히_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            Placed p = standardPaid();
            Instant paidAt = instantOf("paid_at", p.orderId());

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> paymentService.complete(p.orderId(), p.userId()),
                    () -> paymentService.complete(p.orderId(), null),
                    () -> transactions.applyPaid(p.orderId(), "tx-stale")));

            String context = "round " + round;
            assertThat(results.stream().filter(PaymentStateGuardIntegrationTest::failed).toList()).as(context).isEmpty();
            assertThat(results.get(0)).as(context).isEqualTo("CANCELLED");
            assertThat(status(p.orderId())).as("%s: a late payment confirmation must not undo the cancel", context).isEqualTo("CANCELLED");
            assertThat(instantOf("paid_at", p.orderId())).as(context).isEqualTo(paidAt);
            assertThat(instantOf("cancelled_at", p.orderId())).as(context).isEqualTo(NOW);
            assertThat(allStocks()).as("%s: CANCELLED => restored exactly once", context).isEqualTo(INITIAL);
            assertThat(gateway.cancelCalls).as("%s: one customer cancel, one provider call", context).hasSize(1);
        }
    }

    @Test
    void 성공_고객_취소_배송_시작_결제_확정이_동시에_와도_하나만_이기고_PAID로_끝나지_않으며_재고가_승자와_맞는다() throws Exception {
        for (int round = 0; round < 20; round++) {
            newRound();
            Placed p = standardPaid();

            List<Object> results = runTogether(List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> orderService.shipOrder(p.orderId()),
                    () -> transactions.applyPaid(p.orderId(), "tx-stale"),
                    () -> paymentService.complete(p.orderId(), null)));

            assertCancelOrShipExactlyOne(p, results, "round " + round);
        }
    }

    @Test
    void 성공_행_잠금에_줄을_선_고객_취소_배송_시작_결제_확정을_한꺼번에_풀어도_하나만_이기고_PAID로_끝나지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            Placed p = standardPaid();

            List<Object> results = raceBehindRowLock(p.orderId(), List.of(
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"),
                    () -> orderService.shipOrder(p.orderId()),
                    () -> transactions.applyPaid(p.orderId(), "tx-stale")));

            assertCancelOrShipExactlyOne(p, results, "round " + round);
        }
    }

    private void assertCancelOrShipExactlyOne(Placed p, List<Object> results, String context) {
        Object cancelResult = results.get(0);
        Object shipResult = results.get(1);
        for (int i = 2; i < results.size(); i++) {
            assertThat(results.get(i)).as("%s: confirmation #%d never fails, it returns a status", context, i).isInstanceOf(String.class);
        }
        String status = status(p.orderId());
        assertThat(status).as("%s: never PAID again after ship/cancel won", context).isIn("SHIPPED", "CANCELLED");
        if ("SHIPPED".equals(status)) {
            assertThat(shipResult).as(context).isEqualTo("SHIPPED");
            assertThat(cancelResult).as(context).isInstanceOf(OrderNotCancellableException.class);
            assertThat(allStocks()).as("%s: SHIPPED => stock unchanged", context).isEqualTo(HELD);
            assertThat(gateway.cancelCalls).as(context).isEmpty();
            assertThat(instantOf("shipped_at", p.orderId())).as(context).isNotNull();
            assertThat(instantOf("cancelled_at", p.orderId())).as(context).isNull();
        } else {
            assertThat(cancelResult).as(context).isEqualTo("CANCELLED");
            assertThat(shipResult).as(context).isInstanceOf(InvalidOrderStateException.class);
            assertThat(allStocks()).as("%s: CANCELLED => restored exactly once", context).isEqualTo(INITIAL);
            assertThat(gateway.cancelCalls).as(context).hasSize(1);
            assertThat(instantOf("shipped_at", p.orderId())).as(context).isNull();
            assertThat(instantOf("cancelled_at", p.orderId())).as(context).isEqualTo(NOW);
        }
    }

    @Test
    void 성공_진행중인_환불을_관리자가_이어받는_동안_결제_확정이_와도_REFUNDED로_끝나고_재고는_복구되지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            newRound();
            Placed p = shipped();
            refundInFlight(p);
            providerPaid(p);

            List<Object> results = runTogether(List.of(
                    () -> cancelService.refundByAdmin(p.orderId(), "재개"),
                    () -> transactions.applyPaid(p.orderId(), "tx-stale"),
                    () -> paymentService.complete(p.orderId(), null),
                    () -> paymentService.complete(p.orderId(), p.userId())));

            String context = "round " + round;
            assertThat(results.stream().filter(PaymentStateGuardIntegrationTest::failed).toList()).as(context).isEmpty();
            assertThat(results.get(0)).as(context).isEqualTo("REFUNDED");
            assertThat(status(p.orderId())).as(context).isEqualTo("REFUNDED");
            assertThat(allStocks()).as("%s: refund after shipping => no restore", context).isEqualTo(HELD);
            assertThat(gateway.cancelCalls).as(context).hasSize(1).allSatisfy(c -> assertThat(c.requester()).isEqualTo(Requester.ADMIN));
            assertThat(gateway.findCalls.get()).as("%s: confirmations never asked the provider", context).isZero();
        }
    }

    // ------------------------------------------------------------------ 6. admin resumes a stuck CANCELING

    @Test
    void 성공_관리자가_고객이_시작한_막힌_CANCELING_from_PAID를_이어받으면_CANCELLED이고_재고는_정확히_한_번만_돌아온다() {
        seedProducts();
        Placed p = reach("CANCELING_FROM_PAID");
        assertThat(allStocks()).as("stock stays held while CANCELING").isEqualTo(HELD);

        assertThat(cancelService.refundByAdmin(p.orderId(), "고객 요청 재처리")).isEqualTo("CANCELLED");

        assertThat(row(p.orderId())).containsEntry("status", "CANCELLED").containsEntry("cancel_from", "PAID");
        assertThat(instantOf("cancelled_at", p.orderId())).isEqualTo(NOW);
        assertThat(allStocks()).isEqualTo(INITIAL);
        assertThat(gateway.cancelCalls).hasSize(1);
        assertThat(gateway.cancelCalls.get(0)).isEqualTo(new CancelCall(p.orderId(), "고객 요청 재처리", Requester.ADMIN));

        // clicking again: a CANCELLED order is no refund candidate; nothing changes and the stock is not restored twice
        Map<String, Object> finished = row(p.orderId());
        assertCode(catchThrowable(() -> cancelService.refundByAdmin(p.orderId(), "다시")), InvalidOrderStateException.class,
                "INVALID_ORDER_STATE");
        assertThat(row(p.orderId())).isEqualTo(finished);
        assertThat(allStocks()).isEqualTo(INITIAL);
        assertThat(gateway.cancelCalls).hasSize(1);
    }

    @Test
    void 성공_관리자가_막힌_CANCELING_from_SHIPPED를_이어받으면_REFUNDED이고_재고는_복구되지_않는다() {
        seedProducts();
        Placed p = reach("CANCELING_FROM_SHIPPED");

        assertThat(cancelService.refundByAdmin(p.orderId(), "재개")).isEqualTo("REFUNDED");

        assertThat(row(p.orderId())).containsEntry("status", "REFUNDED").containsEntry("cancel_from", "SHIPPED");
        assertThat(allStocks()).isEqualTo(HELD);
        assertThat(gateway.cancelCalls).hasSize(1);
        assertThat(gateway.cancelCalls.get(0).requester()).isEqualTo(Requester.ADMIN);
    }

    @Test
    void 실패_일반_PAID_주문은_관리자_환불_대상이_아니고_행_재고_포트원은_그대로다() {
        seedProducts();
        Placed p = standardPaid();
        Map<String, Object> rowBefore = row(p.orderId());
        Map<String, Integer> stockBefore = allStocks();

        Throwable thrown = catchThrowable(() -> cancelService.refundByAdmin(p.orderId(), "사유"));

        assertCode(thrown, InvalidOrderStateException.class, "INVALID_ORDER_STATE");
        assertUntouched(p, rowBefore, stockBefore, "plain PAID");
    }

    // ------------------------------------------------------------------ 7. V16 orders_canceling_has_origin

    @Test
    void 실패_cancel_from이_NULL인_CANCELING_행은_INSERT도_UPDATE도_거부된다() {
        assertThatThrownBy(() -> insertRaw("CANCELING", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_canceling_has_origin");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();

        String id = insertRawPaid();
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'CANCELING' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_canceling_has_origin");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id)).isEqualTo("PAID");

        // an existing CANCELING row cannot lose its origin either
        jdbc.update("UPDATE orders SET status = 'CANCELING', cancel_from = 'PAID' WHERE id = ?", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET cancel_from = NULL WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_canceling_has_origin");
        assertThat(jdbc.queryForObject("SELECT cancel_from FROM orders WHERE id = ?", String.class, id)).isEqualTo("PAID");
    }

    @Test
    void 성공_cancel_from이_PAID_SHIPPED_DELIVERED인_CANCELING_행은_받아들이고_되돌릴_때_NULL로_비울_수_있다() {
        for (String from : new String[]{"PAID", "SHIPPED", "DELIVERED"}) {
            assertThatCode(() -> insertRaw("CANCELING", from)).as(from).doesNotThrowAnyException();
        }
        String id = insertRawPaid();
        assertThat(jdbc.update("UPDATE orders SET status = 'CANCELING', cancel_from = 'SHIPPED' WHERE id = ?", id)).isEqualTo(1);
        // revert / finish: leaving CANCELING lets cancel_from go back to NULL
        assertThat(jdbc.update("UPDATE orders SET status = 'SHIPPED', cancel_from = NULL WHERE id = ?", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(4);
    }

    @Test
    void 성공_CANCELING이_아닌_행은_cancel_from이_NULL이어도_된다() {
        for (String status : List.of("PENDING_PAYMENT", "PAID", "EXPIRED", "PAYMENT_REVIEW", "CANCELLED", "SHIPPED",
                "DELIVERED", "REFUNDED")) {
            assertThatCode(() -> insertRaw(status, null)).as(status).doesNotThrowAnyException();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(8);
    }

    // ------------------------------------------------------------------ 8. gateway outcome semantics

    @Test
    void 성공_고객_취소에서_결과를_알_수_없는_장애는_CANCELING에_두고_재고도_잡아_둔다() {
        for (CancelMode mode : new CancelMode[]{CancelMode.TIMEOUT_NOT_APPLIED, CancelMode.TIMEOUT_APPLIED}) {
            jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
            gateway.reset();
            seedProducts();
            Placed p = standardPaid();
            gateway.cancelModes.put(p.orderId(), mode);

            Throwable thrown = catchThrowable(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"));

            assertThat(thrown).as(mode.name()).isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                    e -> assertThat(e.isOutcomeUnknown()).isTrue());
            assertThat(row(p.orderId())).as(mode.name()).containsEntry("status", "CANCELING").containsEntry("cancel_from", "PAID")
                    .containsEntry("cancel_reason", "사유");
            assertThat(instantOf("cancelled_at", p.orderId())).as(mode.name()).isNull();
            assertThat(allStocks()).as("%s: stock stays held until the outcome is known", mode).isEqualTo(HELD);
            assertThat(gateway.cancelCalls).as(mode.name()).hasSize(1);
        }
    }

    @Test
    void 실패_고객_취소에서_포트원이_분명히_거절하면_PAID로_되돌리고_재고와_취소_정보를_건드리지_않는다() {
        seedProducts();
        Placed p = standardPaid();
        Map<String, Object> paidRow = row(p.orderId());
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        Throwable thrown = catchThrowable(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"));

        assertThat(thrown).isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                e -> assertThat(e.isOutcomeUnknown()).isFalse());
        assertThat(row(p.orderId())).as("back to exactly the PAID row (no cancel_from / cancel_reason left)").isEqualTo(paidRow);
        assertThat(allStocks()).isEqualTo(HELD);
        assertThat(gateway.cancelCalls).hasSize(1);

        // and the customer can simply try again
        gateway.cancelModes.remove(p.orderId());
        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");
        assertThat(allStocks()).isEqualTo(INITIAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "DELIVERED"})
    void 실패_관리자_환불에서_포트원이_분명히_거절하면_배송_상태로_되돌린다(String from) {
        seedProducts();
        Placed p = reach(from);
        Map<String, Object> beforeRow = row(p.orderId());
        gateway.cancelModes.put(p.orderId(), CancelMode.REJECTED);

        Throwable thrown = catchThrowable(() -> cancelService.refundByAdmin(p.orderId(), "사유"));

        assertThat(thrown).isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                e -> assertThat(e.isOutcomeUnknown()).isFalse());
        assertThat(row(p.orderId())).isEqualTo(beforeRow);
        assertThat(allStocks()).isEqualTo(HELD);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "DELIVERED"})
    void 성공_관리자_환불에서_결과를_알_수_없는_장애는_CANCELING에_두고_재고는_그대로다(String from) {
        seedProducts();
        Placed p = reach(from);
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_APPLIED);

        Throwable thrown = catchThrowable(() -> cancelService.refundByAdmin(p.orderId(), "사유"));

        assertThat(thrown).isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                e -> assertThat(e.isOutcomeUnknown()).isTrue());
        assertThat(row(p.orderId())).containsEntry("status", "CANCELING").containsEntry("cancel_from", from);
        assertThat(allStocks()).isEqualTo(HELD);
    }
}
