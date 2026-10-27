package com.shop.backend.integration;

import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.presentation.dto.ProductDtos.ProductListItem;
import com.shop.backend.presentation.dto.ProductDtos.ProductPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.shop.backend.integration.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "총 N개" of the category list page, on a real Postgres: the list response's {@code total} must equal the number
 * of products in the requested category for every sort, every page and every page size. The sort queries join
 * reviews / orders, so rows that have many reviews, orders, options and images are the ones that would be counted twice
 * if a join leaked into the count.
 *
 * <pre>
 * 바지 7 : b1..b5 plain (b1 has 3 reviews + 2 PAID + 1 EXPIRED order), b6 and b7 created through createProduct with
 *          3 options and 3 images each (b6 has 3 reviews + 2 PAID orders)
 * 신발 5 : s1..s5 plain (s1 has 2 reviews + 1 PAID order)
 * (없음) 3 : u1..u3
 * </pre>
 */
class ProductListTotalIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final int PANTS = 7;
    private static final int SHOES = 5;
    private static final int UNCATEGORIZED = 3;
    private static final int ALL = PANTS + SHOES + UNCATEGORIZED;
    private static final int[] PAGE_SIZES = {1, 2, 3, 7, 50};

    private String b6;

    @BeforeEach
    void givenProducts() {
        for (int i = 1; i <= 5; i++) {
            data.product("b" + i, "바지", 1000 * i, i % 2 == 0 ? 1000 * i + 500 : null, T0.plus(i, ChronoUnit.MINUTES));
        }
        b6 = withOptionsAndImages("바지");
        withOptionsAndImages("바지");
        for (int i = 1; i <= SHOES; i++) {
            data.product("s" + i, "신발", 2000 * i, null, T0.plus(i, ChronoUnit.MINUTES));
        }
        for (int i = 1; i <= UNCATEGORIZED; i++) {
            data.product("u" + i, null, 500 * i, null, T0.plus(i, ChronoUnit.MINUTES));
        }

        data.review("b1", 5);
        data.review("b1", 4);
        data.review("b1", 3);
        data.sale("b1", "PAID", 2, NOW);
        data.sale("b1", "PAID", 3, NOW);
        data.sale("b1", "EXPIRED", 4, NOW);
        data.review(b6, 5);
        data.review(b6, 5);
        data.review(b6, 4);
        data.sale(b6, "PAID", 1, NOW);
        data.sale(b6, "PAID", 1, NOW);
        data.review("s1", 2);
        data.review("s1", 3);
        data.sale("s1", "PAID", 1, NOW);
    }

    private String withOptionsAndImages(String category) {
        List<NewProductOption> options = List.of(
                new NewProductOption("화이트", null, "S", 1000, 1500, 10),
                new NewProductOption("화이트", null, "M", 1100, 1600, 10),
                new NewProductOption("블랙", null, "L", 1200, null, 10));
        return productService.createProduct("옵션 상품", "설명", null, null,
                List.of("https://img.example.com/1.png", "https://img.example.com/2.png", "https://img.example.com/3.png"),
                null, category, null, List.of("태그"), options).getId();
    }

    private ProductPage page(String category, ProductSort sort, int page, int size) {
        return ProductPage.from(productService.listProducts(category, sort, page, size));
    }

    private static int lastPage(int total, int size) {
        return (total + size - 1) / size - 1;
    }

    // ---- first page ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_첫_페이지의_total은_그_카테고리의_상품_수다(ProductSort sort) {
        assertThat(page("바지", sort, 0, 2).total()).as("바지").isEqualTo(PANTS);
        assertThat(page("신발", sort, 0, 2).total()).as("신발").isEqualTo(SHOES);
        assertThat(page(null, sort, 0, 2).total()).as("전체").isEqualTo(ALL);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_total은_페이지_크기가_content보다_작아도_content_개수와_다르다(ProductSort sort) {
        ProductPage first = page("바지", sort, 0, 3);

        assertThat(first.items()).hasSize(3);
        assertThat(first.total()).isEqualTo(PANTS).isNotEqualTo(first.items().size());
    }

    // ---- every page / every size ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_total은_페이지_번호와_크기에_상관없이_같다(ProductSort sort) {
        for (int size : PAGE_SIZES) {
            for (int p = 0; p <= lastPage(PANTS, size); p++) {
                assertThat(page("바지", sort, p, size).total()).as("%s 바지 page=%d size=%d", sort, p, size).isEqualTo(PANTS);
            }
            for (int p = 0; p <= lastPage(SHOES, size); p++) {
                assertThat(page("신발", sort, p, size).total()).as("%s 신발 page=%d size=%d", sort, p, size).isEqualTo(SHOES);
            }
            for (int p = 0; p <= lastPage(ALL, size); p++) {
                assertThat(page(null, sort, p, size).total()).as("%s 전체 page=%d size=%d", sort, p, size).isEqualTo(ALL);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_마지막_페이지_다음_페이지는_비어_있어도_total은_그대로다(ProductSort sort) {
        ProductPage beyond = page("바지", sort, 5, 3); // 7 items, size 3 -> pages 0..2

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasMore()).isFalse();
        assertThat(beyond.total()).isEqualTo(PANTS);
    }

    // ---- hasMore agrees with total ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_hasMore는_다음_페이지가_있을_때만_true이고_total과_맞는다(ProductSort sort) {
        for (int size : PAGE_SIZES) {
            for (String category : new String[]{"바지", "신발", null}) {
                int total = category == null ? ALL : "바지".equals(category) ? PANTS : SHOES;
                for (int p = 0; p <= lastPage(total, size); p++) {
                    ProductPage result = page(category, sort, p, size);

                    assertThat(result.hasMore()).as("%s %s page=%d size=%d", sort, category, p, size)
                            .isEqualTo((long) (p + 1) * size < result.total());
                    assertThat(result.items()).as("items %s %s page=%d size=%d", sort, category, p, size)
                            .hasSize((int) Math.min(size, total - (long) p * size));
                }
            }
        }
    }

    // ---- no join duplication ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_리뷰_주문_옵션_이미지가_있는_상품도_한_번만_세고_모든_페이지를_합치면_total과_같다(ProductSort sort) {
        for (int size : new int[]{1, 2, 3}) {
            List<String> ids = new ArrayList<>();
            long total = -1;
            for (int p = 0; p <= lastPage(PANTS, size); p++) {
                ProductPage result = page("바지", sort, p, size);
                total = result.total();
                result.items().stream().map(ProductListItem::id).forEach(ids::add);
            }

            assertThat(total).as("%s size=%d", sort, size).isEqualTo(PANTS);
            assertThat(ids).as("%s size=%d", sort, size).hasSize(PANTS).doesNotHaveDuplicates();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_리뷰와_주문이_더_늘어도_total은_변하지_않는다(ProductSort sort) {
        long before = page("바지", sort, 0, 2).total();

        for (int i = 0; i < 5; i++) {
            data.review("b1", 5);
            data.review(b6, 4);
            data.sale("b1", "PAID", 1, NOW);
            data.sale(b6, "PAID", 2, NOW);
        }

        assertThat(before).isEqualTo(PANTS);
        assertThat(page("바지", sort, 0, 2).total()).isEqualTo(PANTS);
        assertThat(page(null, sort, 0, 2).total()).isEqualTo(ALL);
    }

    // ---- empty / unknown / normalization ----

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_없는_카테고리면_total은_0이고_items도_비어_있다(ProductSort sort) {
        ProductPage result = page("없는카테고리", sort, 0, 8);

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_카테고리_앞뒤_공백은_무시하고_빈_카테고리는_전체로_센다(ProductSort sort) {
        assertThat(page(" 바지 ", sort, 0, 2).total()).isEqualTo(PANTS);
        assertThat(page("  ", sort, 0, 2).total()).isEqualTo(ALL);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductSort.class)
    void 성공_상품이_하나도_없으면_total은_0이다(ProductSort sort) {
        jdbc.execute("TRUNCATE order_items, reviews, orders, products CASCADE");

        ProductPage result = page(null, sort, 0, 8);

        assertThat(result.total()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void 성공_상품을_지우면_그_카테고리_total이_줄어든다() {
        long before = page("신발", ProductSort.NEWEST, 0, 2).total();

        jdbc.update("DELETE FROM reviews WHERE product_id = 's1'");
        jdbc.update("DELETE FROM order_items WHERE product_id = 's1'");
        jdbc.update("DELETE FROM products WHERE id = 's1'");

        assertThat(before).isEqualTo(SHOES);
        assertThat(page("신발", ProductSort.NEWEST, 0, 2).total()).isEqualTo(SHOES - 1);
        assertThat(page("바지", ProductSort.NEWEST, 0, 2).total()).isEqualTo(PANTS);
    }
}
