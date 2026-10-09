package com.shop.backend.integration;

import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.shop.backend.integration.IntegrationTestConfig.FIXED_NOW;
import static com.shop.backend.integration.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;

/** Best-sold query and ProductService.listBestProducts (clock fixed at FIXED_NOW) on a real Postgres. */
class BestProductsIntegrationTest extends PostgresIntegrationTest {

    private List<String> best(BestPeriod period, int size) {
        return productService.listBestProducts(period, size).stream().map(Product::getId).toList();
    }

    private List<String> soldSince(Instant since) {
        return productRepository.findBestSoldSince(since, PageRequest.of(0, 100)).stream().map(Product::getId).toList();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BestPeriod.class)
    void 성공_기간_시작_시각_정각의_주문은_포함된다(BestPeriod period) {
        data.product("in", null, 1000, null, T0);
        data.sale("in", "PAID", 1, FIXED_NOW.minus(period.window())); // exactly at the start

        assertThat(soldSince(FIXED_NOW.minus(period.window()))).containsExactly("in");
        assertThat(best(period, 1)).containsExactly("in"); // size 1 -> no top-up, so this is the window result
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BestPeriod.class)
    void 실패_기간_시작_직전의_주문과_취소된_주문은_제외된다(BestPeriod period) {
        data.product("early", null, 1000, null, T0);
        data.product("cancelled", null, 1000, null, T0);
        data.sale("early", "PAID", 9, FIXED_NOW.minus(period.window()).minusMillis(1));
        data.sale("cancelled", "CANCELLED", 9, FIXED_NOW.minus(Duration.ofMinutes(5)));

        assertThat(soldSince(FIXED_NOW.minus(period.window()))).isEmpty();
    }

    /** Durations are hard-coded on purpose: changing a window length in BestPeriod must break this test. */
    @ParameterizedTest(name = "{0}: {1} counted, {2} not")
    @CsvSource({
            "REALTIME, PT23H, PT25H",
            "WEEKLY, PT167H, PT169H",   // 6d23h / 7d1h
            "MONTHLY, PT719H, PT721H"}) // 29d23h / 30d1h
    void 성공_기간_길이는_24시간_7일_30일로_고정되어_있다(BestPeriod period, Duration insideAge, Duration outsideAge) {
        data.product("inside", null, 1000, null, T0);
        data.product("outside", null, 1000, null, T0);
        data.sale("inside", "PAID", 1, FIXED_NOW.minus(insideAge));
        data.sale("outside", "PAID", 9, FIXED_NOW.minus(outsideAge)); // would win by quantity if the window were too long

        assertThat(best(period, 1)).containsExactly("inside");
        assertThat(soldSince(FIXED_NOW.minus(period.window()))).containsExactly("inside");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BestPeriod.class)
    void 성공_기간_끝쪽_현재_시각의_주문도_포함된다(BestPeriod period) {
        data.product("fresh", null, 1000, null, T0);
        data.sale("fresh", "PAID", 1, FIXED_NOW);

        assertThat(best(period, 1)).containsExactly("fresh");
    }

    @Test
    void 성공_기간_내_판매량이_같으면_전체_인기순으로_정한다() {
        data.product("qa", null, 1000, null, T0);
        data.product("qb", null, 1000, null, T0);
        data.sale("qa", "PAID", 3, FIXED_NOW.minus(Duration.ofHours(1)));
        data.sale("qb", "PAID", 3, FIXED_NOW.minus(Duration.ofHours(1)));
        data.review("qb", 4);
        data.review("qb", 5); // qb popularity 3 + 2 > qa 3 + 0

        assertThat(soldSince(FIXED_NOW.minus(Duration.ofHours(24)))).containsExactly("qb", "qa");
    }

    /**
     * q1: 2 sold 2h ago, q2: 5 sold 3h ago, q6: 7 sold 3 days ago, q3: 10 sold 40 days ago (outside every window),
     * q4: 3 reviews, q5: nothing. Popular order: q3(10), q6(7), q2(5), q4(3), q1(2), q5(0).
     * Windows: 24h = q2, q1; 7d and 30d = q6, q2, q1.
     */
    private void givenBestScenario() {
        for (String id : List.of("q1", "q2", "q3", "q4", "q5", "q6")) {
            data.product(id, null, 1000, null, T0);
        }
        data.sale("q1", "PAID", 2, FIXED_NOW.minus(Duration.ofHours(2)));
        data.sale("q2", "PAID", 5, FIXED_NOW.minus(Duration.ofHours(3)));
        data.sale("q6", "PAID", 7, FIXED_NOW.minus(Duration.ofDays(3)));
        data.sale("q3", "PAID", 10, FIXED_NOW.minus(Duration.ofDays(40)));
        data.review("q4", 5);
        data.review("q4", 5);
        data.review("q4", 5);
    }

    @Test
    void 성공_판매가_부족하면_인기순으로_채우되_중복은_없다() {
        givenBestScenario();

        assertThat(best(BestPeriod.REALTIME, 2)).containsExactly("q2", "q1");
        // top-up skips q2/q1 (already taken) even though q2 is high in the popular order
        assertThat(best(BestPeriod.REALTIME, 4)).containsExactly("q2", "q1", "q3", "q6");
    }

    @Test
    void 성공_요청_크기가_상품_수보다_커도_있는_만큼만_중복없이_돌려준다() {
        givenBestScenario();

        assertThat(best(BestPeriod.REALTIME, 50)).containsExactly("q2", "q1", "q3", "q6", "q4", "q5");
    }

    @Test
    void 성공_기간이_길어지면_더_오래된_판매도_베스트에_들어온다() {
        givenBestScenario();

        assertThat(best(BestPeriod.WEEKLY, 3)).containsExactly("q6", "q2", "q1");
        assertThat(best(BestPeriod.MONTHLY, 4)).containsExactly("q6", "q2", "q1", "q3"); // q3 only via top-up (40 days old)
    }

    @Test
    void 성공_판매가_전혀_없으면_전체_인기순_그대로다() {
        givenBestScenario();
        jdbc.execute("TRUNCATE order_items, orders CASCADE");

        assertThat(best(BestPeriod.REALTIME, 3)).containsExactly("q4", "q6", "q5");
    }
}
