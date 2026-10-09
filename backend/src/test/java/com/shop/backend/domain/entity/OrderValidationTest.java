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
}
