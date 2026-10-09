package com.shop.backend.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;

import static com.shop.backend.integration.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * orders.created_at is TIMESTAMP WITHOUT TIME ZONE, so a JVM default zone that differs from UTC could shift the
 * 24h best-products window. The concrete subclasses run these tests with the default zone set BEFORE the Spring
 * context/datasource is created (and restored afterwards).
 */
abstract class BestSoldTimeZoneIntegrationTest extends PostgresIntegrationTest {

    private static TimeZone original;

    protected static void useZone(String zoneId) {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
    }

    protected static void restoreZone() {
        TimeZone.setDefault(original);
    }

    protected abstract String zoneId();

    private Instant window24hStart() {
        return Instant.now().minus(Duration.ofHours(24));
    }

    @Test
    void 성공_JVM과_DB_세션이_테스트가_의도한_시간대로_떠_있다() {
        // guards against a cached context created under another zone silently making the other tests meaningless
        assertThat(TimeZone.getDefault().getID()).isEqualTo(zoneId());
        assertThat(jdbc.queryForObject("SELECT current_setting('TimeZone')", String.class)).isEqualTo(zoneId());
    }

    @Test
    void 성공_한_시간_전_주문은_24시간_베스트에_들어가고_25시간_전_주문은_빠진다() {
        data.product("recent", null, 1000, null, T0);
        data.product("old", null, 1000, null, T0);
        data.sale("recent", "PAID", 1, Instant.now().minus(Duration.ofHours(1)));
        data.sale("old", "PAID", 1, Instant.now().minus(Duration.ofHours(25)));

        assertThat(productRepository.findBestSoldSince(window24hStart(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .extracting(p -> p.getId()).containsExactly("recent");
    }

    @Test
    void 성공_DB가_now로_기록한_한_시간_전_주문도_같은_기준으로_24시간_베스트에_들어간다() {
        data.product("sql", null, 1000, null, T0);
        jdbc.update("INSERT INTO orders (id, customer_name, customer_phone, customer_address, total_amount, status, created_at) "
                + "VALUES ('o-sql', 'n', 'p', 'a', 1000, 'PAID', now() - interval '1 hour')");
        jdbc.update("INSERT INTO order_items (id, order_id, product_id, quantity, price) VALUES ('i-sql', 'o-sql', 'sql', 1, 1000)");

        assertThat(productRepository.findBestSoldSince(window24hStart(), org.springframework.data.domain.PageRequest.of(0, 10)))
                .extracting(p -> p.getId()).containsExactly("sql");
    }

    @Test
    void 성공_저장한_주문_시각을_읽어도_같은_순간이다() {
        data.product("p", null, 1000, null, T0);
        Instant created = Instant.parse("2026-03-01T15:30:00Z"); // 00:30 next day in Seoul: crosses a date boundary
        data.sale("p", "PAID", 1, created);

        Instant read = em.createQuery("SELECT o.createdAt FROM Order o", Instant.class).getSingleResult();

        assertThat(read).isEqualTo(created);
    }
}
