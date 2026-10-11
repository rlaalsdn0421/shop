package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCursorTest {

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 성공_마이크로초_시각과_id가_인코딩_디코딩_왕복에서_그대로다() {
        OrderCursor cursor = new OrderCursor(Instant.parse("2026-10-11T01:02:03.123456Z"), "3f2b8c1e-aaaa-bbbb-cccc-1234567890ab");

        assertThat(OrderCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void 성공_커서는_URL에_안전한_문자만_쓴다() {
        String encoded = new OrderCursor(Instant.parse("2026-10-11T01:02:03.123456Z"), "id?>>~").encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!!", "====", "bm8tYmFy"})
    void 실패_형식이_올바르지_않은_커서는_한국어_검증_오류다(String cursor) {
        assertThatThrownBy(() -> OrderCursor.decode(cursor))
                .isInstanceOf(ValidationException.class)
                .hasMessage("커서가 올바르지 않아요.");
    }

    @Test
    void 실패_구분자없음_시각오류_빈_id_긴_커서는_검증_오류다() {
        assertThatThrownBy(() -> OrderCursor.decode(b64("2026-10-11T01:02:03Z"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderCursor.decode(b64("yesterday|o1"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderCursor.decode(b64("2026-10-11T01:02:03Z|"))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderCursor.decode(b64("2026-10-11T01:02:03Z|" + "x".repeat(37))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderCursor.decode("A".repeat(201))).isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_id에_NUL_제어문자_구분자_유니코드가_있거나_비어_있으면_검증_오류다() {
        for (String badId : new String[]{"a\u0000b", "\u0000", "a\nb", "a\tb", "a\u007fb", "a|b", "|", "주문-1", "a b", ""}) {
            assertThatThrownBy(() -> OrderCursor.decode(b64("2026-10-11T01:02:03Z|" + badId)))
                    .as("id %s", badId.replace('\u0000', '0'))
                    .isInstanceOf(ValidationException.class).hasMessage("커서가 올바르지 않아요.");
        }
    }

    @Test
    void 성공_UUID_형식_id와_밑줄_하이픈_id는_통과한다() {
        assertThat(OrderCursor.decode(b64("2026-10-11T01:02:03Z|3f2b8c1e-aaaa-bbbb-cccc-1234567890ab")).id())
                .isEqualTo("3f2b8c1e-aaaa-bbbb-cccc-1234567890ab");
        assertThat(OrderCursor.decode(b64("2026-10-11T01:02:03Z|ord_01-x")).id()).isEqualTo("ord_01-x");
    }

    @Test
    void 실패_극단적인_연도의_시각은_검증_오류다() {
        for (String extreme : new String[]{"+999999999-12-31T23:59:59Z", "-1000000000-01-01T00:00:00Z",
                "1999-12-31T23:59:59Z", "9999-01-01T00:00:01Z", "+10000-01-01T00:00:00Z"}) {
            assertThatThrownBy(() -> OrderCursor.decode(b64(extreme + "|o1")))
                    .as(extreme).isInstanceOf(ValidationException.class).hasMessage("커서가 올바르지 않아요.");
        }
    }

    @Test
    void 성공_허용_범위_경계_시각은_통과한다() {
        assertThat(OrderCursor.decode(b64("2000-01-01T00:00:00Z|o1")).createdAt())
                .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
        assertThat(OrderCursor.decode(b64("9999-01-01T00:00:00Z|o1")).createdAt())
                .isEqualTo(Instant.parse("9999-01-01T00:00:00Z"));
    }
}
