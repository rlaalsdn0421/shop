package com.shop.backend.domain.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingPolicyTest {

    @Test
    void 성공_확정된_정책_값은_배송비_3500원과_무료_기준_50000원이다() {
        assertThat(ShippingPolicy.FEE).isEqualTo(3_500);
        assertThat(ShippingPolicy.FREE_THRESHOLD).isEqualTo(50_000);
    }

    @Test
    void 성공_5만원_미만이면_배송비를_받는다() {
        assertThat(ShippingPolicy.feeFor(49_999)).isEqualTo(3_500);
        assertThat(ShippingPolicy.feeFor(1)).isEqualTo(3_500);
    }

    @Test
    void 성공_정확히_5만원이거나_넘으면_무료다() {
        assertThat(ShippingPolicy.feeFor(50_000)).isZero();
        assertThat(ShippingPolicy.feeFor(50_001)).isZero();
        assertThat(ShippingPolicy.feeFor(Integer.MAX_VALUE)).isZero();
    }

    @Test
    void 실패_0이하_금액은_정상_호출이_아니지만_무료로_처리하지_않는다() {
        assertThat(ShippingPolicy.feeFor(0)).isEqualTo(3_500);
        assertThat(ShippingPolicy.feeFor(-1)).isEqualTo(3_500);
        assertThat(ShippingPolicy.feeFor(Integer.MIN_VALUE)).isEqualTo(3_500);
    }
}
