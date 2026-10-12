package com.shop.backend.infrastructure.payment;

import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real HTTP round trip against a local JDK server (no network, no real PortOne). */
class PortOnePaymentGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long sleepMillis = 0;
    private volatile String body = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PortOnePaymentGateway gateway(String secret) {
        return new PortOnePaymentGateway(secret, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void 성공_결제를_읽고_모르는_필드는_무시하며_인증_헤더를_보낸다() {
        body = "{\"status\":\"PAID\",\"id\":\"o1\",\"storeId\":\"s1\",\"amount\":{\"total\":13500,\"paid\":13500,\"x\":1},"
                + "\"currency\":\"KRW\",\"transactionId\":\"tx-1\",\"paidAt\":\"2026-06-15T12:00:00Z\","
                + "\"channel\":{\"type\":\"LIVE\",\"key\":\"k\",\"name\":\"n\"},\"somethingNew\":{}}";

        Optional<PaymentGateway.Payment> payment = gateway("test-secret").find("o1");

        assertThat(payment).contains(new PaymentGateway.Payment("o1", "s1", "PAID", 13_500L, "KRW", "tx-1", "LIVE"));
        assertThat(authorization.get()).isEqualTo("PortOne test-secret");
        assertThat(path.get()).isEqualTo("/payments/o1");
    }

    @Test
    void 성공_채널_정보가_없으면_채널_타입은_null이다() {
        body = "{\"status\":\"PAID\",\"id\":\"o1\",\"storeId\":\"s1\",\"amount\":{\"total\":1},\"currency\":\"KRW\"}";

        assertThat(gateway("test-secret").find("o1").orElseThrow().channelType()).isNull();
    }

    @Test
    void 성공_TEST_채널_타입을_그대로_읽는다() {
        body = "{\"status\":\"PAID\",\"id\":\"o1\",\"channel\":{\"type\":\"TEST\"}}";

        assertThat(gateway("test-secret").find("o1").orElseThrow().channelType()).isEqualTo("TEST");
    }

    @Test
    void 성공_api_base의_공백과_끝_슬래시는_정리한다() {
        body = "{\"status\":\"PAID\",\"id\":\"o1\"}";
        String base = "  http://127.0.0.1:" + server.getAddress().getPort() + "//  ";

        new PortOnePaymentGateway("test-secret", base).find("o1");

        assertThat(path.get()).isEqualTo("/payments/o1");
    }

    @Test
    void 실패_읽기_타임아웃이_지나면_포트원_장애로_본다() {
        sleepMillis = 1500;
        body = "{\"status\":\"PAID\"}";
        PortOnePaymentGateway slow = new PortOnePaymentGateway("test-secret",
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThatThrownBy(() -> slow.find("o1")).isInstanceOf(PaymentProviderUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
    }

    @Test
    void 성공_포트원이_404면_결제_없음으로_본다() {
        status = 404;
        body = "{\"type\":\"PAYMENT_NOT_FOUND\"}";

        assertThat(gateway("test-secret").find("o1")).isEmpty();
    }

    @Test
    void 실패_5xx와_401은_포트원_장애로_본다() {
        status = 500;
        assertThatThrownBy(() -> gateway("test-secret").find("o1")).isInstanceOf(PaymentProviderUnavailableException.class);
        status = 401;
        assertThatThrownBy(() -> gateway("test-secret").find("o1")).isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    void 실패_연결할_수_없으면_포트원_장애로_보고_비밀값을_메시지에_넣지_않는다() {
        PortOnePaymentGateway down = new PortOnePaymentGateway("super-secret-value", "http://127.0.0.1:1");

        assertThatThrownBy(() -> down.find("o1"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageNotContaining("super-secret-value");
    }

    @Test
    void 실패_API_시크릿이_비어_있으면_호출하지_않고_설정_없음이다() {
        PortOnePaymentGateway off = gateway(" ");

        assertThat(off.isConfigured()).isFalse();
        assertThatThrownBy(() -> off.find("o1")).isInstanceOf(PaymentNotConfiguredException.class);
        assertThat(path.get()).isNull();
    }
}
