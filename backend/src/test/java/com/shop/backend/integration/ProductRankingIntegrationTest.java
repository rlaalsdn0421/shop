package com.shop.backend.integration;

import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.shop.backend.integration.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sort orders against hand-computed data on a real Postgres. Every ordering is a total order
 * (key, then createdAt DESC, then id DESC), so the expected id lists below are exact.
 *
 * <pre>
 * id  cat  price  created  paid  cancelled  reviews(ratings)   orig  discount%
 * p01 A    1000   T+0      5     0          5,5                2000  50
 * p02 A    2000   T+0      5     10         5                  2500  20
 * p03 A    3000   T+0      0     20         -                  -     0
 * p04 A    1000   T+10     1     0          4,4,5              1250  20
 * p05 B    2000   T+0      2     0          -                  2020  0 (0.99%)
 * p06 B    3000   T+0      0     0          -                  -     0
 * p07 B    1000   T+5      0     0          3,4                1010  0 (0.99%)
 * p08 B    4000   T+0      3     0          4                  4041  1
 * p09 -    500    T+0      0     0          -                  501   0
 * p10 -    500    T+0      0     0          -                  -     0
 * p11 -    2000   T+20     4     0          -                  4000  50
 * p12 -    4000   T+0      0     0          -                  -     0
 * </pre>
 */
class ProductRankingIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Map<String, String> CATEGORY = Map.of(
            "p01", "A", "p02", "A", "p03", "A", "p04", "A",
            "p05", "B", "p06", "B", "p07", "B", "p08", "B");

    @BeforeEach
    void givenProducts() {
        data.product("p01", "A", 1000, 2000, T0);
        data.product("p02", "A", 2000, 2500, T0);
        data.product("p03", "A", 3000, null, T0);
        data.product("p04", "A", 1000, 1250, T0.plus(10, ChronoUnit.MINUTES));
        data.product("p05", "B", 2000, 2020, T0);
        data.product("p06", "B", 3000, null, T0);
        data.product("p07", "B", 1000, 1010, T0.plus(5, ChronoUnit.MINUTES));
        data.product("p08", "B", 4000, 4041, T0);
        data.product("p09", null, 500, 501, T0);
        data.product("p10", null, 500, null, T0);
        data.product("p11", null, 2000, 4000, T0.plus(20, ChronoUnit.MINUTES));
        data.product("p12", null, 4000, null, T0);

        data.sale("p01", "PAID", 5, NOW);
        data.sale("p02", "PAID", 5, NOW);
        data.sale("p02", "CANCELLED", 10, NOW); // must not count
        data.sale("p03", "CANCELLED", 20, NOW); // must not count
        data.sale("p04", "PAID", 1, NOW);
        data.sale("p05", "PAID", 2, NOW);
        data.sale("p08", "PAID", 3, NOW);
        data.sale("p11", "PAID", 4, NOW);

        data.review("p01", 5);
        data.review("p01", 5);
        data.review("p02", 5);
        data.review("p04", 4);
        data.review("p04", 4);
        data.review("p04", 5);
        data.review("p07", 3);
        data.review("p07", 4);
        data.review("p08", 4);
    }

    static Stream<Arguments> expectedOrders() {
        return Stream.of(
                // sold + reviews: 7,6,4(T20),4(T10),4(T0),2(T5),2(T0),0...
                Arguments.of(ProductSort.POPULAR, List.of("p01", "p02", "p11", "p04", "p08", "p07", "p05", "p12", "p10", "p09", "p06", "p03")),
                // PAID quantity only: 5,5,4,3,2,1,0...
                Arguments.of(ProductSort.SALES, List.of("p02", "p01", "p11", "p08", "p05", "p04", "p07", "p12", "p10", "p09", "p06", "p03")),
                // review count: 3,2(T5),2(T0),1,1,0...
                Arguments.of(ProductSort.REVIEWS, List.of("p04", "p07", "p01", "p08", "p02", "p11", "p12", "p10", "p09", "p06", "p05", "p03")),
                // avg: 5.0(2 reviews), 5.0(1), 4.33, 4.0, 3.5, then NO reviews (NULLS LAST)
                Arguments.of(ProductSort.RATING, List.of("p01", "p02", "p04", "p08", "p07", "p11", "p12", "p10", "p09", "p06", "p05", "p03")),
                Arguments.of(ProductSort.PRICE_ASC, List.of("p10", "p09", "p04", "p07", "p01", "p11", "p05", "p02", "p06", "p03", "p12", "p08")),
                Arguments.of(ProductSort.PRICE_DESC, List.of("p12", "p08", "p06", "p03", "p11", "p05", "p02", "p04", "p07", "p01", "p10", "p09")),
                Arguments.of(ProductSort.NEWEST, List.of("p11", "p04", "p07", "p12", "p10", "p09", "p08", "p06", "p05", "p03", "p02", "p01")),
                // 50%,50%,20%,20%,1%, then everything under 1% / no discount (0) ordered newest first
                Arguments.of(ProductSort.DISCOUNT, List.of("p11", "p01", "p04", "p02", "p08", "p07", "p12", "p10", "p09", "p06", "p05", "p03")));
    }

    /** Walks every page; zero duplicates and zero missing rows are asserted by the caller via exact list equality. */
    private List<String> walkAllPages(String category, ProductSort sort, int pageSize) {
        List<String> ids = new ArrayList<>();
        int page = 0;
        Page<Product> current;
        do {
            current = productService.listProducts(category, sort, page++, pageSize);
            current.forEach(p -> ids.add(p.getId()));
        } while (current.hasNext());
        return ids;
    }

    private static List<String> inCategory(List<String> all, String category) {
        return category == null ? all : all.stream().filter(id -> category.equals(CATEGORY.get(id))).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectedOrders")
    void 성공_정렬_순서가_손으로_계산한_결과와_같다(ProductSort sort, List<String> expected) {
        for (String category : new String[]{null, "A", "B"}) {
            List<String> expectedForCategory = inCategory(expected, category);

            List<String> onePage = productService.listProducts(category, sort, 0, 100).stream().map(Product::getId).toList();

            assertThat(onePage).as("%s / category=%s", sort, category).containsExactlyElementsOf(expectedForCategory);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expectedOrders")
    void 성공_작은_페이지로_끝까지_넘겨도_중복도_누락도_없다(ProductSort sort, List<String> expected) {
        for (int pageSize : new int[]{1, 3, 5}) {
            for (String category : new String[]{null, "A", "B"}) {
                List<String> walked = walkAllPages(category, sort, pageSize);

                assertThat(walked).as("%s / category=%s / size=%d", sort, category, pageSize)
                        .doesNotHaveDuplicates()
                        .containsExactlyElementsOf(inCategory(expected, category));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_전체_건수는_카테고리_필터를_따른다(ProductSort sort) {
        assertThat(productService.listProducts(null, sort, 0, 5).getTotalElements()).isEqualTo(12);
        assertThat(productService.listProducts("A", sort, 0, 2).getTotalElements()).isEqualTo(4);
        assertThat(productService.listProducts("없는카테고리", sort, 0, 5).getTotalElements()).isZero();
    }

    @Test
    void 성공_리뷰_없는_상품은_평점순에서_리뷰_있는_상품_뒤에_온다() {
        List<String> rating = productService.listProducts(null, ProductSort.RATING, 0, 100).stream().map(Product::getId).toList();

        // p07 has the LOWEST average (3.5) of the reviewed products but still comes before every unreviewed one
        assertThat(rating.indexOf("p07")).isLessThan(rating.indexOf("p11"));
        assertThat(rating.subList(0, 5)).containsExactly("p01", "p02", "p04", "p08", "p07");
    }

    @Test
    void 성공_취소된_주문은_판매순에_반영되지_않는다() {
        List<String> sales = productService.listProducts(null, ProductSort.SALES, 0, 100).stream().map(Product::getId).toList();

        // p03 (20 cancelled) must not beat p01; p02 ties p01 on PAID=5 and wins only by id
        assertThat(sales.indexOf("p03")).isGreaterThan(sales.indexOf("p01"));
        assertThat(sales.subList(0, 2)).containsExactly("p02", "p01");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_정렬_키가_전부_같은_수십_개_상품도_페이지가_겹치거나_빠지지_않는다(ProductSort sort) {
        replaceWithIdenticalProducts(30);

        List<String> walked = walkAllPages(null, sort, 7);

        // identical keys everywhere -> only the tie-breaker orders them: id DESC
        List<String> expected = new ArrayList<>();
        for (int i = 30; i >= 1; i--) {
            expected.add(String.format("t%02d", i));
        }
        assertThat(walked).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    private void replaceWithIdenticalProducts(int count) {
        jdbc.execute("TRUNCATE order_items, reviews, orders, products CASCADE");
        for (int i = 1; i <= count; i++) {
            data.product(String.format("t%02d", i), "C", 1000, null, T0); // same createdAt, price, category
        }
    }
}
