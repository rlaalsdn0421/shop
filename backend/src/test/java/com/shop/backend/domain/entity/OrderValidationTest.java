package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderValidationTest {

    @Test
    void 성공_같은_상품의_여러_줄은_합산된다() {
        Map<String, Integer> sums = OrderValidation.aggregateQuantities(
                List.of(new OrderLine("p1", 2), new OrderLine("p2", 1), new OrderLine("p1", 3)));

        assertThat(sums).containsEntry("p1", 5).containsEntry("p2", 1);
    }

    @Test
    void 성공_int_최대값_한_줄은_합산해도_그대로다() {
        Map<String, Integer> sums = OrderValidation.aggregateQuantities(List.of(new OrderLine("p1", Integer.MAX_VALUE)));

        assertThat(sums).containsEntry("p1", Integer.MAX_VALUE);
    }

    @Test
    void 실패_같은_상품_수량의_합이_int_범위를_넘으면_검증_오류다() {
        List<OrderLine> lines = List.of(new OrderLine("p1", Integer.MAX_VALUE), new OrderLine("p1", Integer.MAX_VALUE));

        assertThatThrownBy(() -> OrderValidation.aggregateQuantities(lines))
                .isInstanceOf(ValidationException.class)
                .hasMessage("주문 수량이 너무 커요.");
    }

    @Test
    void 성공_상품_id가_있으면_통과한다() {
        OrderValidation.validateOrderLines(List.of(new OrderLine("p1", 1)));
    }

    @Test
    void 실패_상품_id가_null_또는_빈_문자열이면_검증_오류다() {
        assertThatThrownBy(() -> OrderValidation.validateOrderLines(List.of(new OrderLine(null, 1))))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderValidation.validateOrderLines(List.of(new OrderLine("  ", 1))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 실패_주문_줄이_null이면_검증_오류다() {
        assertThatThrownBy(() -> OrderValidation.validateOrderLines(Arrays.asList(new OrderLine("p1", 1), null)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_목록_size는_1에서_50까지_통과한다() {
        OrderValidation.validateListSize(1);
        OrderValidation.validateListSize(50);
    }

    @Test
    void 실패_목록_size가_범위_밖이면_검증_오류다() {
        assertThatThrownBy(() -> OrderValidation.validateListSize(0))
                .isInstanceOf(ValidationException.class).hasMessage("주문 수는 1~50개 사이여야 해요.");
        assertThatThrownBy(() -> OrderValidation.validateListSize(51)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OrderValidation.validateListSize(-1)).isInstanceOf(ValidationException.class);
    }

    @Test
    void 성공_올바른_요청_번호는_통과한다() {
        OrderValidation.validateIdempotencyKey("abcd1234");
        OrderValidation.validateIdempotencyKey("a-b_C-0123456789".repeat(4)); // 64 chars
    }

    @Test
    void 실패_요청_번호가_없거나_형식이_틀리면_검증_오류다() {
        for (String bad : new String[]{null, "", "        ", "short12", "a".repeat(65), "has space1", "한글한글한글한글", "a:b:c:d:e:f"}) {
            assertThatThrownBy(() -> OrderValidation.validateIdempotencyKey(bad))
                    .as("key: %s", bad)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("요청 번호(Idempotency-Key)가 올바르지 않아요.");
        }
    }

    @Test
    void 성공_요청_해시는_앞뒤_공백과_상품_순서에_관계없이_같고_64자_hex다() {
        String a = OrderValidation.requestHash(" 홍길동", "010", "서울 ", Map.of("p2", 1, "p1", 3));
        String b = OrderValidation.requestHash("홍길동", "010 ", " 서울", new java.util.LinkedHashMap<>(Map.of("p1", 3, "p2", 1)));

        assertThat(a).isEqualTo(b).matches("[0-9a-f]{64}");
    }

    @Test
    void 실패_내용이_다르면_요청_해시도_다르다() {
        String base = OrderValidation.requestHash("홍길동", "010", "서울", Map.of("p1", 3));

        assertThat(OrderValidation.requestHash("홍길동", "010", "서울", Map.of("p1", 4))).isNotEqualTo(base);
        assertThat(OrderValidation.requestHash("홍길동", "010", "서울", Map.of("p2", 3))).isNotEqualTo(base);
        assertThat(OrderValidation.requestHash("홍길동", "011", "서울", Map.of("p1", 3))).isNotEqualTo(base);
        assertThat(OrderValidation.requestHash("홍길동0", "10", "서울", Map.of("p1", 3))).isNotEqualTo(base);
    }
}
