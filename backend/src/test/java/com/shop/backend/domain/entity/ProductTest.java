package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    private static Product product(int price, Integer originalPrice, List<String> tags) {
        return new Product("n", "d", price, "http://img", 1, null, originalPrice, tags);
    }

    @Test
    void 성공_할인율은_소수점_이하를_버린다() {
        assertThat(product(7500, 10000, null).getDiscountRate()).isEqualTo(25);
        assertThat(product(6667, 10000, null).getDiscountRate()).isEqualTo(33); // 33.33 -> 33
        assertThat(product(3333, 10000, null).getDiscountRate()).isEqualTo(66); // 66.67 -> 66, not 67
        assertThat(product(9900, 10000, null).getDiscountRate()).isEqualTo(1);  // exactly 1%
        assertThat(product(9901, 10000, null).getDiscountRate()).isNull();      // 0.99% -> 0 -> reported as none
        assertThat(product(0, 10000, null).getDiscountRate()).isEqualTo(100);
    }

    @Test
    void 성공_정가가_없으면_할인율은_null이다() {
        assertThat(product(5000, null, null).getDiscountRate()).isNull();
    }

    @Test
    void 성공_할인율이_0퍼센트로_내려가면_null이다() {
        assertThat(product(9999, 10000, null).getDiscountRate()).isNull(); // 0.01%
        assertThat(product(99_000_000, 100_000_000, null).getDiscountRate()).isEqualTo(1);
        assertThat(product(1_999_999_999, 2_000_000_000, null).getDiscountRate()).isNull();
    }

    @Test
    void 성공_큰_가격에서도_int_오버플로_없이_계산한다() {
        // (2_000_000_000 - 1) * 100 does not fit in an int
        assertThat(product(1, 2_000_000_000, null).getDiscountRate()).isEqualTo(99);
    }

    @Test
    void 실패_정가가_판매가_이하인_비정상_데이터는_할인으로_보지_않는다() {
        assertThat(product(5000, 5000, null).getDiscountRate()).isNull();
        assertThat(product(5000, 4000, null).getDiscountRate()).isNull();
    }

    @Test
    void 성공_해시태그가_없으면_null이_아니라_빈_목록이다() {
        assertThat(product(1000, null, null).getHashtags()).isEmpty();
        assertThat(product(1000, null, List.of()).getHashtags()).isEmpty();
    }

    @Test
    void 성공_검증된_해시태그는_8인자_생성자를_거쳐_그대로_돌아온다() {
        List<String> tags = ProductValidation.normalizeHashtags(List.of("＃가나다라마바사", "#Sale", "_x1"));
        Product p = product(1000, 2000, tags);

        assertThat(p.getHashtags()).containsExactlyElementsOf(tags).containsExactly("가나다라마바사", "Sale", "_x1");
    }

    @Test
    void 성공_최대_길이_태그_3개도_저장_후_다시_3개로_나뉜다() {
        List<String> tags = ProductValidation.normalizeHashtags(
                List.of("a".repeat(20), "b".repeat(20), "가".repeat(20)));
        Product p = product(1000, null, tags);

        assertThat(p.getHashtags()).containsExactlyElementsOf(tags);
    }

    @Test
    void 성공_해시태그는_한_칸_공백으로_저장하고_다시_나눠_돌려준다() {
        assertThat(product(1000, null, List.of("여름", "sale")).getHashtags()).containsExactly("여름", "sale");
    }
}
