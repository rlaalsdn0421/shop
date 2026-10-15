package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The idempotency hash with option lines; option-less orders must keep the exact hash they had before V18. */
class OrderRequestHashTest {

    // Computed independently with sha256 over the pre-V18 canonical text (see OrderValidation.requestHash).
    private static final String LEGACY_ONE_LINE = "187e6e5779fd48666a66f9a860fdec9a3d18b36c8b8ac98b9de26e274ea9614d";
    private static final String LEGACY_TWO_LINES = "58c133d56028c42480bed1a827a738f55806dbd8179d9e7b905f2b15d206a87d";

    private static String hash(Map<StockKey, Integer> lines) {
        return OrderValidation.requestHashByStockKey("구매자", "010-0000-0000", "서울", lines);
    }

    @Test
    void 성공_옵션_없는_주문의_해시는_예전과_정확히_같다() {
        assertThat(OrderValidation.requestHash("구매자", "010-0000-0000", "서울", Map.of("p1", 2))).isEqualTo(LEGACY_ONE_LINE);
        assertThat(hash(Map.of(new StockKey("p1", null), 2))).isEqualTo(LEGACY_ONE_LINE);
        assertThat(OrderValidation.requestHash("구매자", "010-0000-0000", "서울", Map.of("pB", 3, "pA", 1)))
                .isEqualTo(LEGACY_TWO_LINES);
        assertThat(hash(Map.of(new StockKey("pB", null), 3, new StockKey("pA", null), 1))).isEqualTo(LEGACY_TWO_LINES);
    }

    @Test
    void 성공_옵션_없는_줄을_가진_요청에서_뽑은_해시도_예전과_같다() {
        Map<StockKey, Integer> aggregated = OrderValidation.aggregateLines(
                List.of(new OrderLine("p1", 1), new OrderLine("p1", 1, "")));

        assertThat(hash(aggregated)).isEqualTo(LEGACY_ONE_LINE);
    }

    @Test
    void 성공_줄_순서와_쪼개기는_해시에_영향이_없다() {
        Map<StockKey, Integer> a = OrderValidation.aggregateLines(List.of(
                new OrderLine("p1", 1, "o1"), new OrderLine("p2", 2), new OrderLine("p1", 3, "o2")));
        Map<StockKey, Integer> b = OrderValidation.aggregateLines(List.of(
                new OrderLine("p1", 3, "o2"), new OrderLine("p1", 1, "o1"), new OrderLine("p2", 1), new OrderLine("p2", 1)));

        assertThat(hash(a)).isEqualTo(hash(b));
    }

    @Test
    void 실패_같은_상품의_옵션만_다르면_해시가_달라서_키_재사용을_거절할_수_있다() {
        String o1 = hash(Map.of(new StockKey("p1", "o1"), 1));
        String o2 = hash(Map.of(new StockKey("p1", "o2"), 1));
        String none = hash(Map.of(new StockKey("p1", null), 1));

        assertThat(o1).isNotEqualTo(o2).isNotEqualTo(none);
        assertThat(o2).isNotEqualTo(none);
    }

    @Test
    void 실패_옵션_id_글자가_다른_줄의_모양을_흉내_낼_수_없다() {
        // "2:p1@1:o=1" must not equal two legacy-looking lines or a product whose id contains '@'
        String option = hash(Map.of(new StockKey("p1", "o"), 1));
        String fakeProduct = hash(Map.of(new StockKey("p1@1:o", null), 1));
        Map<StockKey, Integer> two = new LinkedHashMap<>();
        two.put(new StockKey("p1", null), 1);
        two.put(new StockKey("o", null), 1);

        assertThat(option).isNotEqualTo(fakeProduct).isNotEqualTo(hash(two));
    }

    @Test
    void 성공_같은_상품의_다른_옵션은_따로_합산하고_같은_옵션은_합친다() {
        Map<StockKey, Integer> lines = OrderValidation.aggregateLines(List.of(
                new OrderLine("p1", 1, "o1"), new OrderLine("p1", 2, "o2"), new OrderLine("p1", 4, "o1")));

        assertThat(lines).containsEntry(new StockKey("p1", "o1"), 5).containsEntry(new StockKey("p1", "o2"), 2).hasSize(2);
    }

    @Test
    void 성공_옵션_id가_비어_있으면_옵션_없는_줄과_합친다() {
        Map<StockKey, Integer> lines = OrderValidation.aggregateLines(List.of(
                new OrderLine("p1", 1, ""), new OrderLine("p1", 2, null), new OrderLine("p1", 3, "  ")));

        assertThat(lines).containsOnlyKeys(new StockKey("p1", null)).containsEntry(new StockKey("p1", null), 6);
    }

    @Test
    void 실패_합친_수량이_int_범위를_넘으면_검증_오류다() {
        List<OrderLine> lines = List.of(new OrderLine("p1", Integer.MAX_VALUE, "o1"), new OrderLine("p1", 1, "o1"));

        assertThatThrownBy(() -> OrderValidation.aggregateLines(lines))
                .isInstanceOf(ValidationException.class).hasMessage("주문 수량이 너무 커요.");
    }
}
