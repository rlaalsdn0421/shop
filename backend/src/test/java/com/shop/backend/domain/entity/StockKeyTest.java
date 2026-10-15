package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class StockKeyTest {

    @Test
    void 성공_상품_id_순으로_정렬하고_같은_상품은_옵션_id_순이다() {
        List<StockKey> keys = new ArrayList<>(List.of(
                new StockKey("pB", null), new StockKey("pA", "o2"), new StockKey("pA", "o1"), new StockKey("pC", "o1")));

        Collections.sort(keys);

        assertThat(keys).containsExactly(new StockKey("pA", "o1"), new StockKey("pA", "o2"),
                new StockKey("pB", null), new StockKey("pC", "o1"));
    }

    @Test
    void 성공_옵션_없는_줄과_옵션_줄이_섞여도_요청_순서와_상관없이_같은_순서다() {
        StockKey legacy = new StockKey("p2", null);
        StockKey option = new StockKey("p1", "o9");
        StockKey otherOption = new StockKey("p3", "o1");

        TreeMap<StockKey, Integer> a = new TreeMap<>();
        a.put(otherOption, 1);
        a.put(legacy, 1);
        a.put(option, 1);
        TreeMap<StockKey, Integer> b = new TreeMap<>();
        b.put(option, 1);
        b.put(otherOption, 1);
        b.put(legacy, 1);

        assertThat(a.keySet()).containsExactly(option, legacy, otherOption);
        assertThat(b.keySet()).containsExactlyElementsOf(a.keySet());
    }

    @Test
    void 성공_옵션_없는_키끼리의_순서는_예전_문자열_상품_id_순서와_같다() {
        List<String> ids = List.of("p10", "p2", "P1", "p1", "pA");

        List<String> viaKeys = ids.stream().map(id -> new StockKey(id, null)).sorted().map(StockKey::productId).toList();

        assertThat(viaKeys).isEqualTo(ids.stream().sorted().toList());
    }

    @Test
    void 성공_빈_옵션_id는_옵션_없음과_같은_키다() {
        assertThat(new StockKey("p1", "")).isEqualTo(new StockKey("p1", null));
        assertThat(new StockKey("p1", "  ").optionId()).isNull();
        assertThat(new StockKey("p1", "").compareTo(new StockKey("p1", null))).isZero();
    }

    @Test
    void 실패_같은_상품이라도_옵션이_다르면_다른_키다() {
        assertThat(new StockKey("p1", "o1")).isNotEqualTo(new StockKey("p1", "o2"));
        assertThat(new StockKey("p1", "o1")).isNotEqualTo(new StockKey("p1", null));
        assertThat(new StockKey("p1", "o1").compareTo(new StockKey("p1", "o2"))).isNegative();
    }
}
