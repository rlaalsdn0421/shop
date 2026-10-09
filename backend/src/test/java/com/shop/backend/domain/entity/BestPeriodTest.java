package com.shop.backend.domain.entity;

import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BestPeriodTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void 성공_period가_비어있으면_realtime이다(String value) {
        assertThat(BestPeriod.parse(value)).isEqualTo(BestPeriod.REALTIME);
    }

    @Test
    void 성공_기간_창은_24시간_7일_30일이다() {
        assertThat(BestPeriod.parse("realtime").window()).isEqualTo(Duration.ofHours(24));
        assertThat(BestPeriod.parse("weekly").window()).isEqualTo(Duration.ofDays(7));
        assertThat(BestPeriod.parse("monthly").window()).isEqualTo(Duration.ofDays(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"daily", "WEEKLY", "yearly"})
    void 실패_알_수_없는_period는_거부한다(String value) {
        assertThatThrownBy(() -> BestPeriod.parse(value))
                .isInstanceOf(ValidationException.class)
                .hasMessage("지원하지 않는 기간이에요.");
    }
}
