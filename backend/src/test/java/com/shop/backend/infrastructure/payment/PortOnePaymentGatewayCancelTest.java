package com.shop.backend.infrastructure.payment;

import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** POST /payments/{id}/cancel over a real HTTP round trip against a local JDK server (no real PortOne). */
class PortOnePaymentGatewayCancelTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long sleepMillis = 0;
    private volatile String body = "{\"cancellation\":{\"status\":\"SUCCEEDED\",\"id\":\"c1\",\"totalAmount\":13500}}";
    // When set, GET /payments/{id} answers 200 with this body; otherwise GET gets the same status/body as the cancel POST.
    private volatile String lookupBody = null;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean lookup = lookupBody != null && "GET".equals(exchange.getRequestMethod());
            byte[] bytes = (lookup ? lookupBody : body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(lookup ? 200 : status, bytes.length == 0 ? -1 : bytes.length);
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
    void 성공_전액_취소는_POST로_사유와_요청자만_보내고_인증_헤더를_붙인다() {
        gateway("test-secret").cancel("o1", "단순 변심", PaymentGateway.Requester.CUSTOMER);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/payments/o1/cancel");
        assertThat(authorization.get()).isEqualTo("PortOne test-secret");
        assertThat(contentType.get()).startsWith("application/json");
        // no "amount" = the whole payment is cancelled; no "storeId" = the secret's own store
        assertThat(requestBody.get()).isEqualTo("{\"reason\":\"단순 변심\",\"requester\":\"CUSTOMER\"}");
    }

    @Test
    void 성공_판매자_환불은_요청자가_ADMIN이다() {
        gateway("test-secret").cancel("o1", "하자", PaymentGateway.Requester.ADMIN);

        assertThat(requestBody.get()).contains("\"requester\":\"ADMIN\"");
    }

    @Test
    void 성공_이미_취소된_결제라는_오류는_성공으로_본다() {
        status = 409;
        body = "{\"type\":\"PAYMENT_ALREADY_CANCELLED\",\"message\":\"already\"}";

        assertThatCode(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                .doesNotThrowAnyException();
    }

    @Test
    void 실패_그_밖의_4xx는_명확한_거절이라_결과_알_수_있는_취소_장애다() {
        for (int code : new int[]{400, 401, 403, 404, 409, 422}) {
            status = code;
            body = "{\"type\":\"PAYMENT_NOT_PAID\",\"message\":\"nope\"}";
            assertThatThrownBy(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                    .as("status " + code)
                    .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isFalse());
        }
    }

    @Test
    void 성공_다른_4xx로_거절돼도_결제가_이미_취소됨이면_성공으로_본다() {
        status = 409;
        body = "{\"type\":\"PAYMENT_NOT_PAID\",\"message\":\"cancelled by the PG\"}";
        lookupBody = "{\"status\":\"CANCELLED\",\"id\":\"o1\",\"storeId\":\"s1\",\"amount\":{\"total\":13500},\"currency\":\"KRW\"}";

        assertThatCode(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.ADMIN))
                .doesNotThrowAnyException();
        assertThat(method.get()).isEqualTo("GET");
        assertThat(path.get()).isEqualTo("/payments/o1");
    }

    @Test
    void 실패_4xx이고_결제가_취소됨이_아니면_거절로_본다() {
        status = 409;
        body = "{\"type\":\"PAYMENT_NOT_PAID\"}";
        for (String state : new String[]{"PAID", "PARTIAL_CANCELLED", "READY", "FAILED"}) {
            lookupBody = "{\"status\":\"" + state + "\",\"id\":\"o1\"}";
            assertThatThrownBy(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.ADMIN))
                    .as(state)
                    .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isFalse());
        }
    }

    @Test
    void 성공_PG의_이미_취소_오류를_감싼_502여도_결제가_취소됨이면_성공으로_본다() {
        for (int code : new int[]{500, 502, 503, 504}) {
            status = code;
            body = "{\"type\":\"PG_PROVIDER\",\"message\":\"already cancelled at the PG\"}";
            lookupBody = "{\"status\":\"CANCELLED\",\"id\":\"o1\",\"storeId\":\"s1\",\"amount\":{\"total\":13500},\"currency\":\"KRW\"}";

            assertThatCode(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                    .as("status " + code)
                    .doesNotThrowAnyException();
        }
        assertThat(method.get()).isEqualTo("GET");
    }

    @Test
    void 실패_5xx이고_결제가_취소됨이_아니면_결과를_알_수_없는_장애로_둔다() {
        status = 502;
        body = "{\"type\":\"PG_PROVIDER\"}";
        for (String state : new String[]{"PAID", "PARTIAL_CANCELLED"}) {
            lookupBody = "{\"status\":\"" + state + "\",\"id\":\"o1\"}";
            assertThatThrownBy(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.ADMIN))
                    .as(state)
                    .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isTrue());
        }
    }

    @Test
    void 실패_5xx는_프록시_502_504_포함_취소가_됐을_수도_있어_결과를_알_수_없는_취소_장애다() {
        for (int code : new int[]{500, 502, 503, 504}) {
            status = code;
            body = "{\"type\":\"UNKNOWN\"}";
            assertThatThrownBy(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                    .as("status " + code)
                    .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                            e -> assertThat(e.isOutcomeUnknown()).isTrue());
        }
    }

    @Test
    void 성공_5xx여도_이미_취소된_결제라는_본문이면_성공으로_본다() {
        status = 500;
        body = "{\"type\":\"PAYMENT_ALREADY_CANCELLED\"}";

        assertThatCode(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                .doesNotThrowAnyException();
    }

    @Test
    void 실패_본문이_비었거나_깨진_오류는_이미_취소로_보지_않는다() {
        status = 500;
        for (String broken : new String[]{"", "not json", "[]", "{\"type\":5}"}) {
            body = broken;
            assertThatThrownBy(() -> gateway("test-secret").cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                    .as(broken).isInstanceOf(CancelProviderUnavailableException.class);
        }
    }

    @Test
    void 실패_읽기_타임아웃은_결과를_알_수_없는_취소_장애다() {
        sleepMillis = 1500;
        PortOnePaymentGateway slow = new PortOnePaymentGateway("test-secret",
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThatThrownBy(() -> slow.cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                        e -> assertThat(e.isOutcomeUnknown()).isTrue());
    }

    @Test
    void 실패_연결할_수_없으면_결과를_알_수_없는_취소_장애이고_비밀값을_메시지에_넣지_않는다() {
        PortOnePaymentGateway down = new PortOnePaymentGateway("super-secret-value", "http://127.0.0.1:1");

        assertThatThrownBy(() -> down.cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                .isInstanceOfSatisfying(CancelProviderUnavailableException.class,
                        e -> assertThat(e.isOutcomeUnknown()).isTrue())
                .hasMessageNotContaining("super-secret-value");
    }

    @Test
    void 실패_API_시크릿이_비어_있으면_호출하지_않고_설정_없음이다() {
        PortOnePaymentGateway off = gateway(" ");

        assertThatThrownBy(() -> off.cancel("o1", "x", PaymentGateway.Requester.CUSTOMER))
                .isInstanceOf(PaymentNotConfiguredException.class);
        assertThat(path.get()).isNull();
    }

    @Test
    void 성공_조회는_취소됨_상태를_그대로_알려준다() {
        status = 200;
        body = "{\"status\":\"CANCELLED\",\"id\":\"o1\",\"storeId\":\"s1\",\"amount\":{\"total\":13500},\"currency\":\"KRW\"}";

        assertThat(gateway("test-secret").find("o1").orElseThrow().status()).isEqualTo("CANCELLED");
    }
}
