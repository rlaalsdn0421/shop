package com.shop.backend.integration;

import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.shop.backend.integration.TestData.T0;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two copies of the discount formula together: the JPQL in ProductRepository.DISCOUNT_RATE (sort=discount)
 * and Product.getDiscountRate() (what the API shows). Includes the values that once caused
 * "integer out of range" (originalPrice - price) * 100 overflowing int.
 */
class DiscountParityIntegrationTest extends PostgresIntegrationTest {

    /** id, price, originalPrice, hand-computed percent (null = none / under 1%), minutes after T0. */
    private record Case(String id, int price, Integer originalPrice, Integer expectedRate, int minutes) {
    }

    private static final List<Case> CASES = List.of(
            new Case("d01", 0, null, null, 0),
            new Case("d02", 1000, null, null, 0),
            new Case("d03", 0, 1, 100, 0),
            new Case("d04", 1, 2, 50, 0),
            new Case("d05", 0, 2_000_000_000, 100, 0),
            new Case("d06", 1, 2_000_000_000, 99, 0),            // diff*100 = ~2e11, overflows int
            new Case("d07", 2_147_483_646, 2_147_483_647, null, 0), // 1 won off Integer.MAX_VALUE
            new Case("d08", 990, 1000, 1, 0),                    // exactly 1%
            new Case("d09", 1_000_000_000, 2_000_000_000, 50, 1),
            new Case("d10", 2_000_000_000, 2_147_483_647, 6, 0),
            new Case("d11", 999_999, 1_000_000, null, 0),        // 0.0001%
            new Case("d12", 1, 100, 99, 0),
            new Case("d13", 99, 100, 1, 0),
            new Case("d14", 100, 101, null, 0),                  // 0.99% -> rounds down to 0
            new Case("d15", 1, 21_474_839, 99, 0),               // (orig - price) * 100 just above int max
            new Case("d16", 50, 100, 50, 0),
            new Case("d17", 1, 2, 50, 0),                        // same rate and createdAt as d04 -> id decides
            new Case("d18", 2, 100, 98, 0),
            new Case("d19", 5000, 5001, null, 2),                // tiny discount but newest
            new Case("d20", 2_147_483_646, 2_147_483_647, null, 0),
            new Case("d21", 1000, null, null, 0));               // no original price: ties with the "under 1%" rows by id

    @BeforeEach
    void givenMatrix() {
        for (Case c : CASES) {
            data.product(c.id(), null, c.price(), c.originalPrice(), T0.plus(c.minutes(), ChronoUnit.MINUTES));
        }
    }

    private List<Product> walkAll(int pageSize) {
        List<Product> all = new ArrayList<>();
        int page = 0;
        Page<Product> current;
        do {
            current = productService.listProducts(null, ProductSort.DISCOUNT, page++, pageSize);
            all.addAll(current.getContent());
        } while (current.hasNext());
        return all;
    }

    @Test
    void 성공_Product_getDiscountRate가_손으로_계산한_할인율과_같다() {
        for (Product p : productRepository.findAll()) {
            Case c = CASES.stream().filter(x -> x.id().equals(p.getId())).findFirst().orElseThrow();
            assertThat(p.getDiscountRate()).as("%s (%d -> %d)", c.id(), c.originalPrice(), c.price()).isEqualTo(c.expectedRate());
        }
    }

    @Test
    void 성공_할인율순_DB_정렬이_Product_getDiscountRate_기준_정렬과_같다() {
        List<Product> all = productRepository.findAll();
        // same rule as the SQL: rate (none/<1% = 0) DESC, createdAt DESC, id DESC
        List<String> expected = all.stream()
                .sorted(Comparator.<Product>comparingInt(p -> p.getDiscountRate() == null ? 0 : p.getDiscountRate()).reversed()
                        .thenComparing(Product::getCreatedAt, Comparator.reverseOrder())
                        .thenComparing(Product::getId, Comparator.reverseOrder()))
                .map(Product::getId).toList();

        assertThat(productService.listProducts(null, ProductSort.DISCOUNT, 0, 100).stream().map(Product::getId))
                .hasSize(CASES.size())
                .containsExactlyElementsOf(expected);
        assertThat(walkAll(3).stream().map(Product::getId)).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    @Test
    void 성공_할인율순_DB_정렬이_손으로_계산한_순서와_같다() {
        // 100% (d05,d03) > 99% (d15,d12,d06) > 98% > 50% (d09 is newer, then d17,d16,d04 by id) > 6% > 1% > all "none / under 1%" newest first
        List<String> expected = List.of("d05", "d03", "d15", "d12", "d06", "d18", "d09", "d17", "d16", "d04",
                "d10", "d13", "d08", "d19", "d21", "d20", "d14", "d11", "d07", "d02", "d01");

        assertThat(walkAll(4).stream().map(Product::getId)).containsExactlyElementsOf(expected);
    }
}
