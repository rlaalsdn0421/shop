package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductOptionTest {

    private static ProductOption option(String color, String size, int price, Integer original) {
        return new ProductOption("p1", new NewProductOption(color, null, size, price, original, 3), 0);
    }

    @Test
    void 성공_이름표는_색상_다음_사이즈를_슬래시로_잇고_있는_것만_쓴다() {
        assertThat(option("화이트", "M", 1000, null).label()).isEqualTo("화이트 / M");
        assertThat(option("화이트", null, 1000, null).label()).isEqualTo("화이트");
        assertThat(option(null, "M", 1000, null).label()).isEqualTo("M");
    }

    @Test
    void 성공_이름표는_DB_컬럼_80자_안에_들어간다() {
        String label = option("가".repeat(30), "나".repeat(30), 1000, null).label();

        assertThat(label.length()).isLessThanOrEqualTo(80);
    }

    @Test
    void 성공_할인율은_상품과_같은_식으로_내림해서_계산한다() {
        assertThat(option(null, "M", 7500, 10000).getDiscountRate()).isEqualTo(25);
        assertThat(option(null, "M", 6667, 10000).getDiscountRate()).isEqualTo(33); // 33.33 rounds down
    }

    @Test
    void 성공_정가가_없거나_할인이_1퍼센트_미만이면_할인율이_없다() {
        assertThat(option(null, "M", 1000, null).getDiscountRate()).isNull();
        assertThat(option(null, "M", 9999, 10000).getDiscountRate()).isNull(); // 0.01% -> 0
    }

    @Test
    void 성공_옵션_할인율이_같은_가격의_상품_할인율과_같다() {
        Product product = new Product("n", "d", 6667, "http://img", 1, null, 10000, null);

        assertThat(option(null, "M", 6667, 10000).getDiscountRate()).isEqualTo(product.getDiscountRate());
    }
}
