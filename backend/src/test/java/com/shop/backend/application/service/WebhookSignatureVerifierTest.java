package com.shop.backend.application.service;

import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureVerifierTest {

    // Made-up test key (not a real secret)
    private static final byte[] KEY = "unit-test-webhook-key-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(KEY);
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String TS = String.valueOf(NOW.getEpochSecond());
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"data\":{\"paymentId\":\"p1\"}}";

    private final WebhookSignatureVerifier verifier =
            new WebhookSignatureVerifier(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

    private static String sign(String id, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 성공_올바른_서명을_통과시킨다() throws Exception {
        assertThatCode(() -> verifier.verify("msg_1", TS, sign("msg_1", TS, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void 성공_여러_서명_중_하나만_맞아도_통과한다() throws Exception {
        String header = "v1,AAAA v2,BBBB " + sign("msg_1", TS, BODY);
        assertThatCode(() -> verifier.verify("msg_1", TS, header, BODY)).doesNotThrowAnyException();
    }

    @Test
    void 실패_본문이_바뀌면_거부한다() throws Exception {
        String signature = sign("msg_1", TS, BODY);
        assertThatThrownBy(() -> verifier.verify("msg_1", TS, signature, BODY + " ")).isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_다른_키로_만든_서명은_거부한다() {
        assertThatThrownBy(() -> verifier.verify("msg_1", TS, "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", BODY))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_타임스탬프가_5분을_넘게_어긋나면_거부한다() throws Exception {
        String old = String.valueOf(NOW.getEpochSecond() - 301);
        assertThatThrownBy(() -> verifier.verify("msg_1", old, sign("msg_1", old, BODY), BODY))
                .isInstanceOf(ValidationException.class);
        String edge = String.valueOf(NOW.getEpochSecond() - 300);
        assertThatCode(() -> verifier.verify("msg_1", edge, sign("msg_1", edge, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void 실패_미래_타임스탬프도_5분을_넘으면_거부하고_경계는_통과한다() throws Exception {
        String future = String.valueOf(NOW.getEpochSecond() + 301);
        assertThatThrownBy(() -> verifier.verify("msg_1", future, sign("msg_1", future, BODY), BODY))
                .isInstanceOf(ValidationException.class);
        String edge = String.valueOf(NOW.getEpochSecond() + 300);
        assertThatCode(() -> verifier.verify("msg_1", edge, sign("msg_1", edge, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void 실패_오버플로하는_극단_타임스탬프는_서명이_맞아도_거부한다() throws Exception {
        for (long extreme : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE + 1}) {
            String ts = String.valueOf(extreme);
            assertThatThrownBy(() -> verifier.verify("msg_1", ts, sign("msg_1", ts, BODY), BODY))
                    .as("timestamp %s", ts).isInstanceOf(ValidationException.class);
        }
    }

    @Test
    void 성공_whsec_접두사가_없는_시크릿도_같은_방식으로_검증한다() throws Exception {
        WebhookSignatureVerifier bare = new WebhookSignatureVerifier(Base64.getEncoder().encodeToString(KEY),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatCode(() -> bare.verify("msg_1", TS, sign("msg_1", TS, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void 실패_시크릿이_올바른_Base64가_아니면_설정_없음으로_본다() {
        WebhookSignatureVerifier broken = new WebhookSignatureVerifier("whsec_!!!not base64!!!", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> broken.verify("msg_1", TS, "v1,x", BODY)).isInstanceOf(PaymentNotConfiguredException.class);
    }

    @Test
    void 실패_헤더가_없거나_형식이_틀리면_거부한다() {
        assertThatThrownBy(() -> verifier.verify(null, TS, "v1,x", BODY)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> verifier.verify("msg_1", "abc", "v1,x", BODY)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> verifier.verify("msg_1", TS, null, BODY)).isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_시크릿이_비어_있으면_웹훅을_받지_않는다() {
        WebhookSignatureVerifier off = new WebhookSignatureVerifier("", Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> off.verify("msg_1", TS, "v1,x", BODY)).isInstanceOf(PaymentNotConfiguredException.class);
    }
}
