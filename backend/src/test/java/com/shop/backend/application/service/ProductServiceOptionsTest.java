package com.shop.backend.application.service;

import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductImageRepository;
import com.shop.backend.infrastructure.repository.ProductOptionRepository;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Product registration with options (V18): derived price fields, saved rows, and nothing saved when invalid. */
class ProductServiceOptionsTest {

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductImageRepository imageRepository = mock(ProductImageRepository.class);
    private final ProductOptionRepository optionRepository = mock(ProductOptionRepository.class);
    private final ProductService service = new ProductService(repository, imageRepository, optionRepository,
            Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC), mock(jakarta.persistence.EntityManager.class));

    private static NewProductOption opt(String color, String size, int price, Integer original, int stock) {
        return new NewProductOption(color, color == null ? null : "#FFFFFF", size, price, original, stock);
    }

    private Product create(Integer price, Integer stock, Integer originalPrice, List<NewProductOption> options) {
        when(repository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", "pid");
            return p;
        });
        return service.createProduct("n", "d", price, "http://img", null, stock, null, originalPrice, null, options);
    }

    @SuppressWarnings("unchecked")
    private List<ProductOption> savedOptions() {
        ArgumentCaptor<List<ProductOption>> captor = ArgumentCaptor.forClass(List.class);
        verify(optionRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void 성공_상품_가격은_가장_싼_옵션_가격이고_그_옵션의_정가가_상품_정가가_된다() {
        Product saved = create(null, null, null, List.of(
                opt("화이트", "L", 12000, null, 5), opt("화이트", "M", 9000, 15000, 2), opt("블랙", "M", 10000, 11000, 1)));

        assertThat(saved.getPrice()).isEqualTo(9000);
        assertThat(saved.getOriginalPrice()).isEqualTo(15000);
        assertThat(saved.getDiscountRate()).isEqualTo(40);
        assertThat(saved.isPriceVaries()).isTrue();
    }

    @Test
    void 성공_옵션_가격이_모두_같으면_가격_차이_없음으로_저장한다() {
        Product saved = create(null, null, null, List.of(opt(null, "S", 5000, null, 1), opt(null, "M", 5000, null, 1)));

        assertThat(saved.getPrice()).isEqualTo(5000);
        assertThat(saved.isPriceVaries()).isFalse();
    }

    @Test
    void 성공_옵션이_1개여도_옵션_상품으로_저장한다() {
        Product saved = create(null, null, null, List.of(opt(null, "FREE", 5000, null, 4)));

        assertThat(saved.isPriceVaries()).isFalse();
        assertThat(savedOptions()).hasSize(1);
    }

    @Test
    void 성공_가장_싼_가격이_여러_개면_먼저_적은_옵션의_정가를_쓴다() {
        Product saved = create(null, null, null, List.of(opt(null, "S", 5000, 8000, 1), opt(null, "M", 5000, 6000, 1)));

        assertThat(saved.getOriginalPrice()).isEqualTo(8000);
    }

    @Test
    void 성공_상품_재고는_0으로_저장하고_맨_위_가격_재고_정가는_무시한다() {
        Product saved = create(99999, 77, 123456, List.of(opt(null, "S", 5000, null, 10), opt(null, "M", 6000, null, 20)));

        assertThat(saved.getStock()).isZero();
        assertThat(saved.getPrice()).isEqualTo(5000);
        assertThat(saved.getOriginalPrice()).isNull();
    }

    @Test
    void 성공_옵션_행은_목록_순서가_정렬_순서이고_정리된_값으로_저장한다() {
        create(null, null, null, List.of(opt(" 화이트 ", " M ", 5000, null, 3), opt("블랙", "M", 5000, null, 0)));

        List<ProductOption> rows = savedOptions();
        assertThat(rows).extracting(ProductOption::getProductId).containsOnly("pid");
        assertThat(rows).extracting(ProductOption::getSortOrder).containsExactly(0, 1);
        assertThat(rows).extracting(ProductOption::getColorName).containsExactly("화이트", "블랙");
        assertThat(rows).extracting(ProductOption::getSizeLabel).containsExactly("M", "M");
        assertThat(rows).extracting(ProductOption::getStock).containsExactly(3, 0);
    }

    @Test
    void 성공_옵션이_없거나_비어_있으면_예전_방식으로_저장하고_옵션_행을_만들지_않는다() {
        Product none = create(7500, 3, null, null);
        Product empty = create(7500, 3, null, List.of());

        for (Product p : List.of(none, empty)) {
            assertThat(p.getPrice()).isEqualTo(7500);
            assertThat(p.getStock()).isEqualTo(3);
            assertThat(p.isPriceVaries()).isFalse();
        }
        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void 성공_옵션이_있으면_맨_위_가격과_재고가_잘못돼도_무시한다() {
        Product saved = create(-5, -5, null, List.of(opt(null, "S", 5000, null, 1)));

        assertThat(saved.getPrice()).isEqualTo(5000);
        assertThat(saved.getStock()).isZero();
    }

    @Test
    void 실패_옵션_하나가_잘못되면_상품도_옵션도_저장하지_않는다() {
        assertThatThrownBy(() -> service.createProduct("n", "d", null, "http://img", null, null, null, null, null,
                List.of(opt(null, "S", 5000, null, 1), opt(null, "s", 6000, null, 1))))
                .isInstanceOf(ValidationException.class);

        verify(repository, never()).save(any());
        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void 실패_옵션이_51개면_저장하지_않는다() {
        List<NewProductOption> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> opt(null, "S" + i, 1000, null, 1)).toList();

        assertThatThrownBy(() -> service.createProduct("n", "d", null, "http://img", null, null, null, null, null, tooMany))
                .isInstanceOf(ValidationException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void 실패_옵션이_있어도_이름_설명_이미지_검증은_그대로_한다() {
        assertThatThrownBy(() -> service.createProduct("", "d", null, "http://img", null, null, null, null, null,
                List.of(opt(null, "S", 5000, null, 1))))
                .isInstanceOf(ValidationException.class);

        verify(repository, never()).save(any());
        verify(optionRepository, never()).saveAll(any());
    }

    @Test
    void 성공_상품의_옵션은_정렬_순서로_조회한다() {
        Product product = new Product("n", "d", 1000, "http://img", 0);
        ReflectionTestUtils.setField(product, "id", "pid");
        ProductOption option = new ProductOption("pid", opt(null, "S", 1000, null, 1), 0);
        when(optionRepository.findByProductIdOrderBySortOrderAsc("pid")).thenReturn(List.of(option));

        assertThat(service.getOptions(product)).containsExactly(option);
    }

    @Test
    void 성공_관리자_목록용_옵션_재고_합계를_상품_id별로_돌려준다() {
        when(optionRepository.sumStockByProduct()).thenReturn(List.of(
                new ProductOptionRepository.StockTotal("p1", 7L), new ProductOptionRepository.StockTotal("p2", 0L)));

        assertThat(service.optionStockTotals()).isEqualTo(Map.of("p1", 7L, "p2", 0L));
    }
}
