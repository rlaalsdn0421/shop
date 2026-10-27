package com.shop.backend.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderCancelService.CancelResolution;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.application.service.PaymentGateway.Payment;
import com.shop.backend.application.service.PaymentService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.Role;
import com.shop.backend.domain.entity.User;
import com.shop.backend.domain.error.CancelAlreadyDoneException;
import com.shop.backend.domain.error.CancelProviderStateException;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.OrderStateException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.shop.backend.infrastructure.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seller's "check cancel status" / "abandon cancel" for a stuck CANCELING order on a real Postgres
 * (docs/cancel-resolve-design.md): the provider's answer decides, the row lock keeps webhook / customer retry / seller
 * from double-restoring stock, and the provider lookup never runs inside a transaction. Full SpringBootTest so the
 * real security chain (401/403) is part of it; only the provider is faked ({@link StubGateway}, @Primary).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "portone.store-id=" + OrderCancelResolveIntegrationTest.STORE_ID,
        "portone.webhook-secret=" + OrderCancelResolveIntegrationTest.WEBHOOK_SECRET,
        "portone.allow-test-channel=false"})
class OrderCancelResolveIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresIntegrationTest.POSTGRES;

    static final String STORE_ID = "store-test-0001";
    // Made-up key, base64 of "integration-test-webhook-key-0123" (not a real secret)
    static final String WEBHOOK_SECRET = "whsec_aW50ZWdyYXRpb24tdGVzdC13ZWJob29rLWtleS0wMTIz";
    private static final byte[] WEBHOOK_KEY = "integration-test-webhook-key-0123".getBytes(StandardCharsets.UTF_8);
    private static final int ROUNDS = 12;

    /** How the fake provider answers cancel() for one payment id (default OK). */
    enum CancelMode {
        OK, REJECTED, TIMEOUT_NOT_APPLIED, TIMEOUT_APPLIED
    }

    record CancelCall(String paymentId) {
    }

    /** Fake provider; thread safe because the race tests call it from several threads. */
    public static class StubGateway implements PaymentGateway {
        final Map<String, Payment> payments = new ConcurrentHashMap<>();
        final Map<String, CancelMode> cancelModes = new ConcurrentHashMap<>();
        final List<CancelCall> cancelCalls = new CopyOnWriteArrayList<>();
        final AtomicInteger findCalls = new AtomicInteger();
        /** How many find() calls ran inside an open transaction (must stay 0: no connection held during the lookup). */
        final AtomicInteger findCallsInsideTransaction = new AtomicInteger();
        volatile boolean unavailable;
        volatile boolean configured = true;

        void reset() {
            payments.clear();
            cancelModes.clear();
            cancelCalls.clear();
            findCalls.set(0);
            findCallsInsideTransaction.set(0);
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
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                findCallsInsideTransaction.incrementAndGet();
            }
            if (unavailable) {
                throw new PaymentProviderUnavailableException();
            }
            return Optional.ofNullable(payments.get(paymentId));
        }

        @Override
        public void cancel(String paymentId, String reason, Requester requester) {
            cancelCalls.add(new CancelCall(paymentId));
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
    static class ResolveTestConfig {
        @Bean
        @Primary
        StubGateway stubGateway() {
            return new StubGateway();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderCancelService cancelService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private StubGateway gateway;

    private TestData data;
    private final Logger serviceLogger = (Logger) LoggerFactory.getLogger(OrderCancelService.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        TestDatabaseGuard.requireContainerDatabase(dataSource, POSTGRES.getJdbcUrl(), POSTGRES.getDatabaseName());
        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        data = new TestData(em, txManager);
        gateway.reset();
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        serviceLogger.detachAppender(logs);
    }

    // ------------------------------------------------------------------ helpers

    private record Placed(String userId, String orderId, int total) {
    }

    private void product(String id, int stock) {
        data.product(id, null, 1000, null, TestData.T0);
        jdbc.update("UPDATE products SET stock = ? WHERE id = ?", stock, id);
    }

    private int stock(String id) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id);
    }

    private String statusOf(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String text(String column, String orderId) {
        return jdbc.queryForObject("SELECT " + column + " FROM orders WHERE id = ?", String.class, orderId);
    }

    /** Every column a transition can touch. */
    private Map<String, Object> row(String orderId) {
        return jdbc.queryForMap("SELECT status, expires_at, paid_at, payment_transaction_id, shipped_at, delivered_at, "
                + "cancelled_at, cancel_from, cancel_reason FROM orders WHERE id = ?", orderId);
    }

    /** Every row of every table a resolve could touch, as text (a changed column anywhere shows up). */
    private Map<String, List<String>> dump() {
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (String table : List.of("orders", "order_items", "products", "product_options")) {
            all.put(table, jdbc.queryForList("SELECT t::text FROM " + table + " t ORDER BY 1", String.class));
        }
        return all;
    }

    private Placed place(String userId, OrderLine... lines) {
        Order order = orderService.createOrder(userId, "key-" + UUID.randomUUID(), "구매자", "010-0000-0000", "서울", List.of(lines));
        return new Placed(userId, order.getId(), order.getTotalAmount());
    }

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

    /** Customer cancel whose provider call timed out without effect: CANCELING, cancel_from PAID, provider still PAID. */
    private void customerCancelInFlight(Placed p) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유"))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        gateway.findCalls.set(0);
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
    }

    /** Seller refund whose provider call timed out without effect: CANCELING, cancel_from SHIPPED/DELIVERED. */
    private void refundInFlight(Placed p) {
        gateway.cancelModes.put(p.orderId(), CancelMode.TIMEOUT_NOT_APPLIED);
        assertThatThrownBy(() -> cancelService.refundByAdmin(p.orderId(), "하자"))
                .isInstanceOf(CancelProviderUnavailableException.class);
        gateway.cancelModes.remove(p.orderId());
        gateway.cancelCalls.clear();
        gateway.findCalls.set(0);
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
    }

    /** PAID origin, stock taken (10 -> 7), cancel stuck. */
    private Placed stuckFromPaid() {
        product("p1", 10);
        Placed p = paid(data.user(), new OrderLine("p1", 3));
        customerCancelInFlight(p);
        return p;
    }

    private Placed stuckFromShipped() {
        product("p1", 10);
        Placed p = shipped(data.user(), new OrderLine("p1", 3));
        refundInFlight(p);
        return p;
    }

    private Placed stuckFromDelivered() {
        product("p1", 10);
        Placed p = delivered(data.user(), new OrderLine("p1", 3));
        refundInFlight(p);
        return p;
    }

    private void providerSays(String orderId, String status) {
        gateway.payments.put(orderId, new Payment(orderId, STORE_ID, status, 1000L, "KRW", "tx-" + orderId.substring(0, 8), "LIVE"));
    }

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private void webhookCancelled(String paymentId) throws Exception {
        String body = "{\"type\":\"Transaction.Cancelled\",\"timestamp\":\"2026-06-15T12:00:00Z\",\"data\":{\"paymentId\":\""
                + paymentId + "\",\"storeId\":\"" + STORE_ID + "\",\"transactionId\":\"tx-1\"}}";
        String id = "msg_" + UUID.randomUUID();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        paymentService.handleWebhook(id, ts, sign(id, ts, body), body);
    }

    private static void assertCode(Throwable t, Class<? extends OrderStateException> type, String code) {
        assertThat(t).isInstanceOfSatisfying(type, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    private void assertLookupNeverInsideTransaction() {
        assertThat(gateway.findCallsInsideTransaction.get()).as("provider lookup inside a transaction").isZero();
    }

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

    /** Holds the order row FOR UPDATE, starts all tasks, waits until every one is queued on that lock, then releases it. */
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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
    }

    /** A racer may only lose with the documented 409s, never with a 500-style exception. */
    /** A check that finished the order says CANCELLED; one that read the already finished order has no provider status. */
    private static void assertFinishedCheck(Object result, String status) {
        assertThat(result).isInstanceOf(CancelResolution.class);
        CancelResolution r = (CancelResolution) result;
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.providerStatus() == null || r.providerStatus().equals("CANCELLED")).as("providerStatus %s", r.providerStatus()).isTrue();
    }

    private static void assertOnlyConflicts(List<Object> results) {
        for (Object result : results) {
            if (result instanceof Throwable t) {
                assertThat(t).isInstanceOfAny(InvalidOrderStateException.class, CancelAlreadyDoneException.class,
                        CancelProviderStateException.class);
            }
        }
    }

    // ------------------------------------------------------------------ check: provider says CANCELLED

    @Test
    void 성공_확인_결제완료발_취소중_주문은_결제사가_취소했으면_CANCELLED가_되고_재고는_정확히_한_번_돌아온다() throws Exception {
        Placed p = stuckFromPaid();
        assertThat(stock("p1")).isEqualTo(7);
        providerSays(p.orderId(), "CANCELLED");

        CancelResolution first = cancelService.checkCancelByAdmin(p.orderId(), "seller-1");

        assertThat(first).isEqualTo(new CancelResolution("CANCELLED", "CANCELLED"));
        Map<String, Object> done = row(p.orderId());
        assertThat(done).containsEntry("status", "CANCELLED");
        assertThat(done.get("cancelled_at")).isNotNull();
        assertThat(stock("p1")).isEqualTo(10);
        assertThat(gateway.cancelCalls).as("the seller check never cancels at the provider").isEmpty();
        assertLookupNeverInsideTransaction();

        // check again: idempotent, no provider call, nothing changes
        int findsAfterFirst = gateway.findCalls.get();
        Map<String, List<String>> dumpDone = dump();
        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1")).isEqualTo(new CancelResolution("CANCELLED", null));
        assertThat(gateway.findCalls.get()).as("an already finished order is answered without the provider").isEqualTo(findsAfterFirst);
        assertThat(dump()).isEqualTo(dumpDone);

        // the webhook path afterwards and a customer retry do not restore stock a second time
        webhookCancelled(p.orderId());
        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")).isEqualTo("CANCELLED");
        assertThat(stock("p1")).isEqualTo(10);
        assertThat(dump()).isEqualTo(dumpDone);
    }

    @Test
    void 성공_확인_웹훅이_먼저_마무리한_주문을_확인하면_재고는_다시_늘지_않고_CANCELLED를_돌려준다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        webhookCancelled(p.orderId());
        assertThat(stock("p1")).isEqualTo(10);
        Map<String, List<String>> dumpDone = dump();

        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1").status()).isEqualTo("CANCELLED");

        assertThat(dump()).isEqualTo(dumpDone);
    }

    @Test
    void 성공_확인_배송후발_취소중_주문은_REFUNDED가_되고_재고는_그대로다() {
        Placed shipped = stuckFromShipped();
        providerSays(shipped.orderId(), "CANCELLED");

        assertThat(cancelService.checkCancelByAdmin(shipped.orderId(), "seller-1")).isEqualTo(new CancelResolution("REFUNDED", "CANCELLED"));

        assertThat(statusOf(shipped.orderId())).isEqualTo("REFUNDED");
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(row(shipped.orderId()).get("cancelled_at")).isNotNull();
    }

    @Test
    void 성공_확인_배송완료발_취소중_주문도_REFUNDED가_되고_재고는_그대로다() {
        Placed delivered = stuckFromDelivered();
        providerSays(delivered.orderId(), "CANCELLED");

        assertThat(cancelService.checkCancelByAdmin(delivered.orderId(), "seller-1").status()).isEqualTo("REFUNDED");

        assertThat(statusOf(delivered.orderId())).isEqualTo("REFUNDED");
        assertThat(stock("p1")).isEqualTo(7);
    }

    // ------------------------------------------------------------------ check: nothing may change

    @Test
    void 실패_확인_결제사가_취소됨이_아니면_주문_상품_옵션_어느_행도_바뀌지_않는다() {
        Placed p = stuckFromPaid();
        for (String providerStatus : List.of("PAID", "PARTIAL_CANCELLED", "READY", "FAILED", "PAY_PENDING")) {
            providerSays(p.orderId(), providerStatus);
            Map<String, List<String>> before = dump();

            CancelResolution result = cancelService.checkCancelByAdmin(p.orderId(), "seller-1");

            assertThat(result).as(providerStatus).isEqualTo(new CancelResolution("CANCELING", providerStatus));
            assertThat(dump()).as(providerStatus).isEqualTo(before);
        }
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).isEmpty();
        assertLookupNeverInsideTransaction();
    }

    @Test
    void 실패_확인_결제사에_그런_결제가_없으면_NOT_FOUND를_알리고_아무것도_바꾸지_않는다() {
        Placed p = stuckFromPaid();
        gateway.payments.remove(p.orderId());
        Map<String, List<String>> before = dump();

        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1")).isEqualTo(new CancelResolution("CANCELING", "NOT_FOUND"));

        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 실패_확인_취소됨이어도_다른_상점이거나_결제_id가_다르면_마무리하지_않는다() {
        Placed p = stuckFromPaid();
        Map<String, List<String>> before = dump();

        gateway.payments.put(p.orderId(), new Payment(p.orderId(), "other-store", "CANCELLED", 1000L, "KRW", "tx", "LIVE"));
        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1")).isEqualTo(new CancelResolution("CANCELING", "CANCELLED"));
        gateway.payments.put(p.orderId(), new Payment("someone-elses-order", STORE_ID, "CANCELLED", 1000L, "KRW", "tx", "LIVE"));
        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1")).isEqualTo(new CancelResolution("CANCELING", "CANCELLED"));

        assertThat(dump()).isEqualTo(before);
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_확인_결제사_조회_실패_결제_설정_없음_없는_주문_취소중_아님은_각각_예외이고_아무것도_바꾸지_않는다() {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        Map<String, List<String>> before = dump();

        gateway.unavailable = true;
        assertThatThrownBy(() -> cancelService.checkCancelByAdmin(p.orderId(), "s")).isInstanceOf(PaymentProviderUnavailableException.class);
        gateway.unavailable = false;
        gateway.configured = false;
        assertThatThrownBy(() -> cancelService.checkCancelByAdmin(p.orderId(), "s")).isInstanceOf(PaymentNotConfiguredException.class);
        gateway.configured = true;
        assertThatThrownBy(() -> cancelService.checkCancelByAdmin(UUID.randomUUID().toString(), "s")).isInstanceOf(OrderNotFoundException.class);

        assertThat(dump()).isEqualTo(before);
        assertThat(logs.list).as("no log line for a failure").isEmpty();

        // a PAID / SHIPPED order is not CANCELING: 409 INVALID_ORDER_STATE, no provider call
        product("p2", 5);
        Placed paidOrder = paid(data.user(), new OrderLine("p2", 1));
        Placed shippedOrder = shipped(data.user(), new OrderLine("p2", 1));
        Map<String, List<String>> beforeOthers = dump();
        for (Placed other : List.of(paidOrder, shippedOrder)) {
            assertCode(catchThrowable(() -> cancelService.checkCancelByAdmin(other.orderId(), "s")),
                    InvalidOrderStateException.class, "INVALID_ORDER_STATE");
        }
        assertThat(gateway.findCalls.get()).isZero();
        assertThat(dump()).isEqualTo(beforeOthers);
    }

    // ------------------------------------------------------------------ abandon: back to where it came from

    @Test
    void 성공_포기_결제완료발_주문은_PAID로_돌아가고_취소_흔적이_지워지며_재고는_그대로다() {
        product("p1", 10);
        Placed p = paid(data.user(), new OrderLine("p1", 3));
        Map<String, Object> beforeCancel = row(p.orderId());
        customerCancelInFlight(p);
        assertThat(text("cancel_from", p.orderId())).isEqualTo("PAID");
        assertThat(text("cancel_reason", p.orderId())).isEqualTo("사유");

        CancelResolution result = cancelService.abandonCancelByAdmin(p.orderId(), "seller-1");

        assertThat(result).isEqualTo(new CancelResolution("PAID", "PAID"));
        assertThat(row(p.orderId())).as("exactly the row before the cancel was requested").isEqualTo(beforeCancel);
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(gateway.cancelCalls).isEmpty();
        assertLookupNeverInsideTransaction();
    }

    @Test
    void 성공_포기_배송중발_배송완료발_주문은_각각_원래_상태로_돌아가고_재고는_그대로다() {
        Placed shipped = stuckFromShipped();
        Map<String, Object> shippedBefore = row(shipped.orderId());
        assertThat(text("cancel_from", shipped.orderId())).isEqualTo("SHIPPED");

        assertThat(cancelService.abandonCancelByAdmin(shipped.orderId(), "seller-1").status()).isEqualTo("SHIPPED");

        assertThat(statusOf(shipped.orderId())).isEqualTo("SHIPPED");
        assertThat(text("cancel_from", shipped.orderId())).isNull();
        assertThat(text("cancel_reason", shipped.orderId())).isNull();
        assertThat(row(shipped.orderId()).get("shipped_at")).isEqualTo(shippedBefore.get("shipped_at"));
        assertThat(stock("p1")).isEqualTo(7);

        jdbc.execute("TRUNCATE order_items, reviews, orders, products, users CASCADE");
        Placed delivered = stuckFromDelivered();
        assertThat(cancelService.abandonCancelByAdmin(delivered.orderId(), "seller-1").status()).isEqualTo("DELIVERED");
        assertThat(statusOf(delivered.orderId())).isEqualTo("DELIVERED");
        assertThat(text("cancel_from", delivered.orderId())).isNull();
        assertThat(stock("p1")).isEqualTo(7);
    }

    @Test
    void 실패_포기_결제사에_그런_결제가_없으면_되돌리지_않고_결제_정보_없음_CANCEL_PROVIDER_STATE다() {
        Placed p = stuckFromPaid();
        gateway.payments.remove(p.orderId());
        Map<String, List<String>> before = dump();

        Throwable thrown = catchThrowable(() -> cancelService.abandonCancelByAdmin(p.orderId(), "seller-1"));

        assertCode(thrown, CancelProviderStateException.class, "CANCEL_PROVIDER_STATE");
        assertThat(thrown.getMessage()).isEqualTo("결제사에 이 주문의 결제 정보가 없어요. PortOne 콘솔에서 확인해 주세요.");
        assertThat(dump()).isEqualTo(before);
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
        assertThat(text("cancel_from", p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 실패_포기_결제완료여도_결제_id나_상점이_다르면_되돌리지_않고_행도_재고도_그대로다() {
        Placed p = stuckFromPaid();
        Payment[] wrong = {
                new Payment("someone-elses-order", STORE_ID, "PAID", 1000L, "KRW", "tx", "LIVE"),
                new Payment(p.orderId(), "other-store", "PAID", 1000L, "KRW", "tx", "LIVE"),
                new Payment(p.orderId(), null, "PAID", 1000L, "KRW", "tx", "LIVE"),
                new Payment(p.orderId(), "", "PAID", 1000L, "KRW", "tx", "LIVE")};
        for (Payment bad : wrong) {
            gateway.payments.put(p.orderId(), bad);
            Map<String, List<String>> before = dump();

            Throwable thrown = catchThrowable(() -> cancelService.abandonCancelByAdmin(p.orderId(), "seller-1"));

            assertCode(thrown, CancelProviderStateException.class, "CANCEL_PROVIDER_STATE");
            assertThat(thrown.getMessage()).contains("PAID");
            assertThat(dump()).as(bad.toString()).isEqualTo(before);
        }
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
        assertThat(stock("p1")).isEqualTo(7);
        // the same order with the right id and store is then revertible
        providerSays(p.orderId(), "PAID");
        assertThat(cancelService.abandonCancelByAdmin(p.orderId(), "seller-1").status()).isEqualTo("PAID");
    }

    @Test
    void 실패_포기_결제는_있는데_상태가_비었으면_UNKNOWN으로_거절하고_아무것도_바꾸지_않는다() {
        Placed p = stuckFromPaid();
        for (String blank : new String[]{null, "", "   "}) {
            gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, blank, 1000L, "KRW", "tx", "LIVE"));
            Map<String, List<String>> before = dump();

            Throwable thrown = catchThrowable(() -> cancelService.abandonCancelByAdmin(p.orderId(), "seller-1"));

            assertCode(thrown, CancelProviderStateException.class, "CANCEL_PROVIDER_STATE");
            assertThat(thrown.getMessage()).contains("UNKNOWN");
            assertThat(dump()).as("[%s]", blank).isEqualTo(before);

            // check says UNKNOWN too and changes nothing
            assertThat(cancelService.checkCancelByAdmin(p.orderId(), "seller-1")).isEqualTo(new CancelResolution("CANCELING", "UNKNOWN"));
            assertThat(dump()).isEqualTo(before);
        }
    }

    @Test
    void 실패_결제_설정의_상점_id가_비어_있으면_확인과_포기_모두_결제사를_부르기_전에_설정_없음이고_아무것도_바꾸지_않는다() {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        Map<String, List<String>> before = dump();
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(paymentService, "storeId");
        try {
            for (String blank : new String[]{"", "   "}) {
                org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "storeId", blank);

                assertThatThrownBy(() -> cancelService.checkCancelByAdmin(p.orderId(), "s")).as("check [%s]", blank)
                        .isInstanceOf(PaymentNotConfiguredException.class);
                assertThatThrownBy(() -> cancelService.abandonCancelByAdmin(p.orderId(), "s")).as("abandon [%s]", blank)
                        .isInstanceOf(PaymentNotConfiguredException.class);
            }
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "storeId", original);
        }

        assertThat(gateway.findCalls.get()).isZero();
        assertThat(dump()).isEqualTo(before);
        assertThat(logs.list).isEmpty();
        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "s").status()).as("restored setting works again").isEqualTo("CANCELLED");
    }

    @Test
    void 성공_포기한_주문은_고객이_다시_취소하면_새로_취소되고_재고는_한_번만_돌아온다() {
        Placed p = stuckFromPaid();
        cancelService.abandonCancelByAdmin(p.orderId(), "seller-1");
        assertThat(statusOf(p.orderId())).isEqualTo("PAID");

        assertThat(cancelService.cancelByCustomer(p.orderId(), p.userId(), "다시")).isEqualTo("CANCELLED");

        assertThat(gateway.cancelCalls).hasSize(1);
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 성공_포기_뒤_결제사에서_나중에_취소_웹훅이_오면_CANCELLED로_마무리하고_재고는_한_번만_돌아온다() throws Exception {
        Placed p = stuckFromPaid();
        cancelService.abandonCancelByAdmin(p.orderId(), "seller-1");
        assertThat(statusOf(p.orderId())).isEqualTo("PAID");
        assertThat(stock("p1")).isEqualTo(7);

        providerSays(p.orderId(), "CANCELLED"); // the seller cancels it in the provider console later
        webhookCancelled(p.orderId());

        assertThat(statusOf(p.orderId())).isEqualTo("CANCELLED");
        assertThat(stock("p1")).isEqualTo(10);
        webhookCancelled(p.orderId());
        assertThat(stock("p1")).isEqualTo(10);
    }

    // ------------------------------------------------------------------ abandon: refused, nothing may change

    @Test
    void 실패_포기_결제사가_이미_취소했으면_CANCEL_ALREADY_DONE이고_행도_재고도_그대로다() {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        Map<String, List<String>> before = dump();

        assertCode(catchThrowable(() -> cancelService.abandonCancelByAdmin(p.orderId(), "s")),
                CancelAlreadyDoneException.class, "CANCEL_ALREADY_DONE");

        assertThat(dump()).isEqualTo(before);
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
        assertThat(logs.list).isEmpty();
        // and "check" then finishes it, which is what the message tells the seller to do
        assertThat(cancelService.checkCancelByAdmin(p.orderId(), "s").status()).isEqualTo("CANCELLED");
        assertThat(stock("p1")).isEqualTo(10);
    }

    @Test
    void 실패_포기_결제사가_부분취소_등_그_밖의_상태면_CANCEL_PROVIDER_STATE이고_행도_재고도_그대로다() {
        Placed p = stuckFromPaid();
        for (String providerStatus : List.of("PARTIAL_CANCELLED", "READY", "FAILED", "PAY_PENDING")) {
            providerSays(p.orderId(), providerStatus);
            Map<String, List<String>> before = dump();

            Throwable thrown = catchThrowable(() -> cancelService.abandonCancelByAdmin(p.orderId(), "s"));

            assertCode(thrown, CancelProviderStateException.class, "CANCEL_PROVIDER_STATE");
            assertThat(thrown.getMessage()).contains(providerStatus);
            assertThat(dump()).as(providerStatus).isEqualTo(before);
        }
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
    }

    @Test
    void 실패_포기_조회_실패_설정_없음_없는_주문_취소중_아님은_예외이고_아무것도_바꾸지_않는다() {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "PAID");
        Map<String, List<String>> before = dump();

        gateway.unavailable = true;
        assertThatThrownBy(() -> cancelService.abandonCancelByAdmin(p.orderId(), "s")).isInstanceOf(PaymentProviderUnavailableException.class);
        gateway.unavailable = false;
        gateway.configured = false;
        assertThatThrownBy(() -> cancelService.abandonCancelByAdmin(p.orderId(), "s")).isInstanceOf(PaymentNotConfiguredException.class);
        gateway.configured = true;
        assertThatThrownBy(() -> cancelService.abandonCancelByAdmin(UUID.randomUUID().toString(), "s")).isInstanceOf(OrderNotFoundException.class);
        assertThat(dump()).isEqualTo(before);

        product("p2", 5);
        Placed paidOrder = paid(data.user(), new OrderLine("p2", 1));
        cancelService.cancelByCustomer(paidOrder.orderId(), paidOrder.userId(), "x"); // CANCELLED
        Placed shippedOrder = shipped(data.user(), new OrderLine("p2", 1));
        Map<String, List<String>> beforeOthers = dump();
        for (Placed other : List.of(paidOrder, shippedOrder)) {
            assertCode(catchThrowable(() -> cancelService.abandonCancelByAdmin(other.orderId(), "s")),
                    InvalidOrderStateException.class, "INVALID_ORDER_STATE");
        }
        assertThat(dump()).isEqualTo(beforeOthers);
        assertThat(logs.list).isEmpty();
    }

    // ------------------------------------------------------------------ races on one CANCELING order

    @Test
    void 성공_경합_결제사가_취소한_상태에서_확인_포기_웹훅_고객재시도가_동시에_와도_CANCELLED_하나로_끝나고_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            newRound();
            Placed p = stuckFromPaid();
            providerSays(p.orderId(), "CANCELLED");

            List<Object> r = runTogether(List.of(
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "a"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "b"),
                    () -> cancelService.abandonCancelByAdmin(p.orderId(), "c"),
                    () -> cancelService.abandonCancelByAdmin(p.orderId(), "d"),
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook-done";
                    },
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")));

            assertFinishedCheck(r.get(0), "CANCELLED");
            assertFinishedCheck(r.get(1), "CANCELLED");
            assertThat(r.get(4)).isEqualTo("webhook-done");
            assertThat(r.get(5)).isEqualTo("CANCELLED");
            for (int abandon : new int[]{2, 3}) {
                assertThat(r.get(abandon)).isInstanceOfAny(CancelAlreadyDoneException.class, InvalidOrderStateException.class);
            }
            assertThat(statusOf(p.orderId())).isEqualTo("CANCELLED");
            assertThat(stock("p1")).as("restored exactly once, round %d", round).isEqualTo(10);
            assertThat(gateway.cancelCalls.size()).isLessThanOrEqualTo(1);
            assertLookupNeverInsideTransaction();
        }
    }

    @Test
    void 성공_경합_결제사가_결제완료인_채로_포기와_확인이_동시에_와도_PAID로_한_번만_돌아가고_재고는_그대로다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            newRound();
            Placed p = stuckFromPaid();
            providerSays(p.orderId(), "PAID");

            List<Object> r = runTogether(List.of(
                    () -> cancelService.abandonCancelByAdmin(p.orderId(), "a"),
                    () -> cancelService.abandonCancelByAdmin(p.orderId(), "b"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "c"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "d")));

            assertOnlyConflicts(r);
            assertThat(r.get(0) instanceof Throwable && r.get(1) instanceof Throwable).as("at least one abandon wins, round %d", round).isFalse();
            for (int abandon : new int[]{0, 1}) {
                if (!(r.get(abandon) instanceof Throwable)) {
                    assertThat(r.get(abandon)).isEqualTo(new CancelResolution("PAID", "PAID"));
                }
            }
            for (int check : new int[]{2, 3}) {
                if (!(r.get(check) instanceof Throwable)) {
                    // read before the revert: still CANCELING at that moment, and the provider said PAID
                    assertThat(r.get(check)).isEqualTo(new CancelResolution("CANCELING", "PAID"));
                } else {
                    assertThat(r.get(check)).as("read after the revert").isInstanceOf(InvalidOrderStateException.class);
                }
            }
            assertThat(statusOf(p.orderId())).isEqualTo("PAID");
            assertThat(text("cancel_from", p.orderId())).isNull();
            assertThat(stock("p1")).isEqualTo(7);
            assertThat(gateway.cancelCalls).isEmpty();
            assertLookupNeverInsideTransaction();
        }
    }

    @Test
    void 성공_경합_포기하는_사이_결제사에서_취소되고_웹훅이_와도_최종은_CANCELLED_하나이고_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            newRound();
            Placed p = stuckFromPaid();
            providerSays(p.orderId(), "PAID");

            List<Object> r = runTogether(List.of(
                    () -> cancelService.abandonCancelByAdmin(p.orderId(), "a"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "b"),
                    () -> {
                        // the seller cancels in the provider console, then the webhook arrives
                        gateway.markCancelled(p.orderId());
                        webhookCancelled(p.orderId());
                        return "webhook-done";
                    }));

            assertOnlyConflicts(r);
            assertThat(r.get(2)).isEqualTo("webhook-done");
            // abandon: reverted to PAID before the webhook finished (the documented short window), or refused
            if (!(r.get(0) instanceof Throwable)) {
                assertThat(r.get(0)).isEqualTo(new CancelResolution("PAID", "PAID"));
            }
            if (!(r.get(1) instanceof Throwable)) {
                assertThat(((CancelResolution) r.get(1)).status()).isIn("CANCELING", "CANCELLED");
            }
            assertThat(statusOf(p.orderId())).as("round %d", round).isEqualTo("CANCELLED");
            assertThat(stock("p1")).as("restored exactly once, round %d", round).isEqualTo(10);
            assertLookupNeverInsideTransaction();
        }
    }

    @Test
    void 성공_경합_행_잠금_뒤에_줄을_선_확인_웹훅_고객재시도가_차례로_풀려도_CANCELLED_하나이고_재고는_한_번만_돌아온다() throws Exception {
        for (int round = 0; round < 5; round++) {
            newRound();
            Placed p = stuckFromPaid();
            providerSays(p.orderId(), "CANCELLED");

            List<Object> r = raceBehindRowLock(p.orderId(), List.of(
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "a"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "b"),
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook-done";
                    },
                    () -> cancelService.cancelByCustomer(p.orderId(), p.userId(), "사유")));

            assertFinishedCheck(r.get(0), "CANCELLED");
            assertFinishedCheck(r.get(1), "CANCELLED");
            assertThat(r.get(2)).isEqualTo("webhook-done");
            assertThat(r.get(3)).isEqualTo("CANCELLED");
            assertThat(statusOf(p.orderId())).isEqualTo("CANCELLED");
            assertThat(stock("p1")).isEqualTo(10);
            assertThat(gateway.cancelCalls.size()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void 성공_경합_배송중발_취소중_주문을_확인과_웹훅이_동시에_마무리해도_REFUNDED_하나이고_재고는_그대로다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            newRound();
            Placed p = stuckFromShipped();
            providerSays(p.orderId(), "CANCELLED");

            List<Object> r = runTogether(List.of(
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "a"),
                    () -> cancelService.checkCancelByAdmin(p.orderId(), "b"),
                    () -> {
                        webhookCancelled(p.orderId());
                        return "webhook-done";
                    }));

            assertFinishedCheck(r.get(0), "REFUNDED");
            assertFinishedCheck(r.get(1), "REFUNDED");
            assertThat(r.get(2)).isEqualTo("webhook-done");
            assertThat(statusOf(p.orderId())).isEqualTo("REFUNDED");
            assertThat(stock("p1")).isEqualTo(7);
        }
    }

    // ------------------------------------------------------------------ HTTP through the real security chain

    private String tokenOf(String username, Role role) {
        User user = new User(username, username + "@example.com", "hash", role);
        new TransactionTemplate(txManager).executeWithoutResult(s -> em.persist(user));
        return "Bearer " + jwtService.generateToken(user);
    }

    private String userIdOf(String username) {
        return jdbc.queryForObject("SELECT id FROM users WHERE username = ?", String.class, username);
    }

    @Test
    void 실패_HTTP_로그인_없이_또는_엉터리_토큰으로는_401이고_아무것도_바뀌지_않는다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        Map<String, List<String>> before = dump();

        for (String action : List.of("check", "abandon")) {
            mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/" + action))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/" + action)
                            .header("Authorization", "Bearer not.a.token"))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(dump()).isEqualTo(before);
        assertThat(gateway.findCalls.get()).isZero();
    }

    @Test
    void 실패_HTTP_일반_회원_토큰은_403이고_결제사도_주문도_건드리지_않는다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        String userToken = tokenOf("plain_user", Role.USER);
        Map<String, List<String>> before = dump();

        for (String action : List.of("check", "abandon")) {
            mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/" + action).header("Authorization", userToken))
                    .andExpect(status().isForbidden());
        }

        assertThat(dump()).isEqualTo(before);
        assertThat(gateway.findCalls.get()).isZero();
    }

    @Test
    void 성공_HTTP_판매자가_취소_상태를_확인하면_마무리되고_본문_캐시_헤더_로그의_수정한_사람이_맞다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        String token = tokenOf("seller_one", Role.SELLER);

        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.providerStatus").value("CANCELLED"));

        assertThat(statusOf(p.orderId())).isEqualTo("CANCELLED");
        assertThat(stock("p1")).isEqualTo(10);
        String sellerId = userIdOf("seller_one");
        assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
                .contains("orderId=" + p.orderId()).contains("action=check").contains("actor=" + sellerId));
    }

    @Test
    void 성공_HTTP_관리자가_취소를_포기하면_원래_상태로_돌아가고_결제사가_아직_안_취소한_확인은_취소중으로_남는다() throws Exception {
        Placed p = stuckFromShipped();
        providerSays(p.orderId(), "PAID");
        String token = tokenOf("admin_one", Role.ADMIN);

        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELING"))
                .andExpect(jsonPath("$.providerStatus").value("PAID"));
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");

        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.providerStatus").value("PAID"));

        assertThat(statusOf(p.orderId())).isEqualTo("SHIPPED");
        assertThat(stock("p1")).isEqualTo(7);
        String adminId = userIdOf("admin_one");
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("action=abandon").contains("status=SHIPPED").contains("actor=" + adminId));
    }

    @Test
    void 실패_HTTP_오류는_404_409_코드_503으로_매핑되고_아무것도_바꾸지_않는다() throws Exception {
        Placed p = stuckFromPaid();
        String token = tokenOf("seller_two", Role.SELLER);
        product("p2", 5);
        Placed paidOrder = paid(data.user(), new OrderLine("p2", 1));

        mockMvc.perform(post("/api/admin/orders/" + UUID.randomUUID() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/orders/" + paidOrder.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));

        providerSays(p.orderId(), "CANCELLED");
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_ALREADY_DONE"))
                .andExpect(jsonPath("$.error").isNotEmpty());

        providerSays(p.orderId(), "PARTIAL_CANCELLED");
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PARTIAL_CANCELLED")));

        gateway.payments.remove(p.orderId());
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"))
                .andExpect(jsonPath("$.error").value("결제사에 이 주문의 결제 정보가 없어요. PortOne 콘솔에서 확인해 주세요."));
        providerSays(p.orderId(), "PAID");
        gateway.payments.put(p.orderId(), new Payment("someone-elses-order", STORE_ID, "PAID", 1000L, "KRW", "tx", "LIVE"));
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"));
        providerSays(p.orderId(), "PAID");

        Map<String, List<String>> before = dump();
        gateway.unavailable = true;
        for (String action : List.of("check", "abandon")) {
            mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/" + action).header("Authorization", token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        }
        gateway.unavailable = false;
        assertThat(dump()).isEqualTo(before);
        assertThat(statusOf(p.orderId())).isEqualTo("CANCELING");
        assertThat(stock("p1")).isEqualTo(7);
        assertThat(logs.list).as("no resolve log line for any 404/409/503").isEmpty();
    }

    @Test
    void 성공_HTTP_이미_끝난_주문을_확인하면_결제사를_묻지_않고_providerStatus는_null이다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "CANCELLED");
        cancelService.checkCancelByAdmin(p.orderId(), "first");
        gateway.findCalls.set(0);
        String token = tokenOf("seller_three", Role.SELLER);

        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.providerStatus").value(org.hamcrest.Matchers.nullValue()));

        assertThat(gateway.findCalls.get()).isZero();
    }

    @Test
    void 실패_HTTP_상점_id_설정이_비어_있으면_확인과_포기_모두_503_PAYMENT_NOT_CONFIGURED이고_결제사를_부르지_않는다() throws Exception {
        Placed p = stuckFromPaid();
        providerSays(p.orderId(), "PAID");
        String token = tokenOf("seller_four", Role.SELLER);
        Map<String, List<String>> before = dump();
        Object original = org.springframework.test.util.ReflectionTestUtils.getField(paymentService, "storeId");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "storeId", "");
            for (String action : List.of("check", "abandon")) {
                mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/" + action).header("Authorization", token))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"));
            }
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(paymentService, "storeId", original);
        }

        assertThat(gateway.findCalls.get()).isZero();
        assertThat(dump()).isEqualTo(before);
    }

    @Test
    void 성공_HTTP_결제사_상태_문자열은_응답_409_메시지_로그_모두에서_걸러지고_40자로_잘린다() throws Exception {
        Placed p = stuckFromPaid();
        String token = tokenOf("seller_five", Role.SELLER);
        Map<String, List<String>> before = dump();
        String raw = "PAID\nforged\r\u001b[31m 취소";
        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, raw, 1000L, "KRW", "tx", "LIVE"));

        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELING"))
                .andExpect(jsonPath("$.providerStatus").value("PAID?forged???31m???"));
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("상태가 PAID?forged???31m???이라")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\u001b"))))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\n"))));

        gateway.payments.put(p.orderId(), new Payment(p.orderId(), STORE_ID, "Z".repeat(200), 1000L, "KRW", "tx", "LIVE"));
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/check").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerStatus").value("Z".repeat(40)));
        mockMvc.perform(post("/api/admin/orders/" + p.orderId() + "/cancel/abandon").header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("상태가 " + "Z".repeat(40) + "이라")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Z".repeat(41)))));

        assertThat(dump()).isEqualTo(before);
        assertThat(logs.list).hasSize(2).allSatisfy(e -> assertThat(e.getFormattedMessage())
                .doesNotContain("\n").doesNotContain("\r").doesNotContain("\u001b").doesNotContain("취소")
                .doesNotContain("Z".repeat(41)));
        assertThat(logs.list.get(0).getFormattedMessage()).contains("providerStatus=PAID?forged???31m???");
    }
}
