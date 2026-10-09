package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSortTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void 성공_sort가_비어있으면_최신순이다(String value) {
        assertThat(ProductSort.parse(value)).isEqualTo(ProductSort.NEWEST);
    }

    @Test
    void 성공_모든_sort_값을_파싱한다() {
        assertThat(ProductSort.parse("price_asc")).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(ProductSort.parse("price_desc")).isEqualTo(ProductSort.PRICE_DESC);
        assertThat(ProductSort.parse("reviews")).isEqualTo(ProductSort.REVIEWS);
        assertThat(ProductSort.parse("rating")).isEqualTo(ProductSort.RATING);
        assertThat(ProductSort.parse("sales")).isEqualTo(ProductSort.SALES);
        assertThat(ProductSort.parse("popular")).isEqualTo(ProductSort.POPULAR);
        assertThat(ProductSort.parse("discount")).isEqualTo(ProductSort.DISCOUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRICE_ASC", "price", "random", "DISCOUNT"})
    void 실패_알_수_없는_sort는_거부한다(String value) {
        assertThatThrownBy(() -> ProductSort.parse(value))
                .isInstanceOf(ValidationException.class)
                .hasMessage("지원하지 않는 정렬이에요.");
    }
}
