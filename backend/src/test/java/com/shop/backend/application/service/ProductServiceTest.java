package com.shop.backend.application.service;

import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductService service = new ProductService(repository, CLOCK);
    private final Page<Product> page = new PageImpl<>(List.of());

    private static Product product(String id) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        return p;
    }

    private Pageable captureSortedPageable(String category, Sort.Direction direction) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        if (category == null) {
            verify(repository).findAll(captor.capture());
        } else {
            verify(repository).findAllByCategory(eq(category), captor.capture());
        }
        return captor.getValue();
    }

    // ---- listProducts: which query per sort ----

    @Test
    void 성공_최신순은_createdAt_id_내림차순_쿼리를_쓴다() {
        when(repository.findAllByOrderByCreatedAtDescIdDesc(any())).thenReturn(page);

        assertThat(service.listProducts(null, ProductSort.NEWEST, 1, 8)).isSameAs(page);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAllByOrderByCreatedAtDescIdDesc(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(8);
    }

    @Test
    void 성공_최신순에_카테고리가_있으면_카테고리_쿼리를_쓰고_공백은_다듬는다() {
        when(repository.findAllByCategoryOrderByCreatedAtDescIdDesc(eq("신발"), any())).thenReturn(page);

        assertThat(service.listProducts(" 신발 ", ProductSort.NEWEST, 0, 8)).isSameAs(page);
    }

    @Test
    void 성공_빈_카테고리는_전체로_본다() {
        when(repository.findByPopularDesc(isNull(), any())).thenReturn(page);

        assertThat(service.listProducts("  ", ProductSort.POPULAR, 0, 8)).isSameAs(page);
    }

    @Test
    void 성공_가격_낮은순은_가격_오름차순_뒤에_createdAt_id_내림차순을_붙인다() {
        when(repository.findAll(any(Pageable.class))).thenReturn(page);

        service.listProducts(null, ProductSort.PRICE_ASC, 0, 8);

        assertThat(captureSortedPageable(null, Sort.Direction.ASC).getSort().toString())
                .isEqualTo("price: ASC,createdAt: DESC,id: DESC");
    }

    @Test
    void 성공_가격_높은순은_가격_내림차순_뒤에_createdAt_id_내림차순을_붙인다() {
        when(repository.findAllByCategory(eq("상의"), any())).thenReturn(page);

        service.listProducts("상의", ProductSort.PRICE_DESC, 0, 8);

        assertThat(captureSortedPageable("상의", Sort.Direction.DESC).getSort().toString())
                .isEqualTo("price: DESC,createdAt: DESC,id: DESC");
    }

    @Test
    void 성공_리뷰순_평점순_판매순_인기순은_각각_자기_쿼리를_쓴다() {
        when(repository.findByReviewCountDesc(eq("가방"), any())).thenReturn(page);
        when(repository.findByRatingDesc(eq("가방"), any())).thenReturn(page);
        when(repository.findBySalesDesc(eq("가방"), any())).thenReturn(page);
        when(repository.findByPopularDesc(eq("가방"), any())).thenReturn(page);

        service.listProducts("가방", ProductSort.REVIEWS, 0, 8);
        service.listProducts("가방", ProductSort.RATING, 0, 8);
        service.listProducts("가방", ProductSort.SALES, 0, 8);
        service.listProducts("가방", ProductSort.POPULAR, 0, 8);

        verify(repository).findByReviewCountDesc(eq("가방"), any());
        verify(repository).findByRatingDesc(eq("가방"), any());
        verify(repository).findBySalesDesc(eq("가방"), any());
        verify(repository).findByPopularDesc(eq("가방"), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 성공_할인율순은_자기_쿼리를_정렬_없는_Pageable로_쓴다() {
        when(repository.findByDiscountDesc(eq("신발"), any())).thenReturn(page);
        when(repository.findByDiscountDesc(isNull(), any())).thenReturn(page);

        assertThat(service.listProducts(" 신발 ", ProductSort.DISCOUNT, 1, 8)).isSameAs(page);
        service.listProducts(null, ProductSort.DISCOUNT, 0, 8);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByDiscountDesc(eq("신발"), captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse(); // the order lives in the @Query
        verify(repository).findByDiscountDesc(isNull(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 성공_집계_정렬_쿼리에는_정렬이_없는_Pageable을_넘긴다() {
        when(repository.findBySalesDesc(isNull(), any())).thenReturn(page);

        service.listProducts(null, ProductSort.SALES, 2, 8);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findBySalesDesc(isNull(), captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse(); // the order lives in the @Query
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    // ---- listBestProducts ----

    @Test
    void 성공_기간별_집계_시작시각은_정확히_24시간_7일_30일_전이다() {
        List<Product> sold = List.of(product("a"));
        when(repository.findBestSoldSince(any(), any())).thenReturn(sold);
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of());

        service.listBestProducts(BestPeriod.REALTIME, 1);
        service.listBestProducts(BestPeriod.WEEKLY, 1);
        service.listBestProducts(BestPeriod.MONTHLY, 1);

        verify(repository).findBestSoldSince(eq(NOW.minus(Duration.ofHours(24))), any());
        verify(repository).findBestSoldSince(eq(NOW.minus(Duration.ofDays(7))), any());
        verify(repository).findBestSoldSince(eq(NOW.minus(Duration.ofDays(30))), any());
    }

    @Test
    void 성공_판매된_상품이_size_이상이면_인기순으로_채우지_않는다() {
        List<Product> sold = List.of(product("a"), product("b"));
        when(repository.findBestSoldSince(any(), any())).thenReturn(sold);

        assertThat(service.listBestProducts(BestPeriod.WEEKLY, 2)).containsExactlyElementsOf(sold);

        verify(repository, never()).listByPopularDesc(any(), any());
    }

    @Test
    void 성공_판매된_상품이_모자라면_인기순으로_채우되_중복없이_순서를_지킨다() {
        Product a = product("a");
        Product b = product("b");
        Product c = product("c");
        Product d = product("d");
        Product e = product("e");
        when(repository.findBestSoldSince(any(), any())).thenReturn(List.of(a, b));
        // popular list contains the already-sold a and b first
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of(b, c, a, d, e));

        List<Product> result = service.listBestProducts(BestPeriod.MONTHLY, 4);

        assertThat(result).containsExactly(a, b, c, d);
        // asks for size + already-sold so duplicates can't leave the section short
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).listByPopularDesc(isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(6);
    }

    @Test
    void 성공_판매가_하나도_없으면_전부_인기순으로_채운다() {
        Product c = product("c");
        Product d = product("d");
        when(repository.findBestSoldSince(any(), any())).thenReturn(List.of());
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of(c, d));

        assertThat(service.listBestProducts(BestPeriod.REALTIME, 4)).containsExactly(c, d);
    }

    @Test
    void 성공_판매_조회는_size만큼만_요청한다() {
        List<Product> sold = List.of(product("a"), product("b"));
        when(repository.findBestSoldSince(any(), any())).thenReturn(sold);

        service.listBestProducts(BestPeriod.WEEKLY, 2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findBestSoldSince(any(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    void 성공_판매된_상품이_size보다_하나_모자라면_하나만_채운다() {
        Product a = product("a");
        Product b = product("b");
        Product c = product("c");
        Product x = product("x");
        List<Product> sold = List.of(a, b, c);
        when(repository.findBestSoldSince(any(), any())).thenReturn(sold);
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of(a, x, b, c));

        assertThat(service.listBestProducts(BestPeriod.WEEKLY, 4)).containsExactly(a, b, c, x);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).listByPopularDesc(isNull(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(7);
    }

    @Test
    void 성공_인기순에서_중복을_빼고도_모자라면_있는_만큼만_돌려준다() {
        Product a = product("a");
        Product b = product("b");
        when(repository.findBestSoldSince(any(), any())).thenReturn(List.of());
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of(a, b));

        assertThat(service.listBestProducts(BestPeriod.REALTIME, 4)).containsExactly(a, b);
    }

    @Test
    void 성공_판매된_상품이_일부_있고_인기순도_모자라면_판매된_것_뒤에_중복없이_붙인다() {
        Product a = product("a");
        Product b = product("b");
        when(repository.findBestSoldSince(any(), any())).thenReturn(List.of(a, b));
        when(repository.listByPopularDesc(isNull(), any())).thenReturn(List.of(b, a));

        assertThat(service.listBestProducts(BestPeriod.REALTIME, 4)).containsExactly(a, b);
    }

    @Test
    void 성공_판매된_상품이_size보다_많아도_저장소에_size만큼만_요청한다() {
        List<Product> sold = List.of(product("a"));
        when(repository.findBestSoldSince(any(), any())).thenReturn(sold);

        service.listBestProducts(BestPeriod.MONTHLY, 1);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findBestSoldSince(any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
        verify(repository, never()).listByPopularDesc(any(), any());
    }

    // ---- createProduct ----

    @Test
    void 성공_정가와_정규화된_해시태그로_상품을_저장한다() {
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product saved = service.createProduct("n", "d", 7500, "http://img", 3, " 신발 ", 10000, List.of(" ##여름 ", "#Sale", "sale"));

        assertThat(saved.getOriginalPrice()).isEqualTo(10000);
        assertThat(saved.getDiscountRate()).isEqualTo(25);
        assertThat(saved.getHashtags()).containsExactly("여름", "Sale");
        assertThat(saved.getCategory()).isEqualTo("신발");
    }

    @Test
    void 성공_정가와_해시태그가_없으면_할인_없는_상품으로_저장한다() {
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product saved = service.createProduct("n", "d", 7500, "http://img", 3, null, null, null);

        assertThat(saved.getOriginalPrice()).isNull();
        assertThat(saved.getDiscountRate()).isNull();
        assertThat(saved.getHashtags()).isEmpty();
    }

    @Test
    void 실패_정가가_판매가_이하면_저장하지_않는다() {
        assertThatThrownBy(() -> service.createProduct("n", "d", 7500, "http://img", 3, null, 7500, null))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void 실패_해시태그가_잘못되면_저장하지_않는다() {
        assertThatThrownBy(() -> service.createProduct("n", "d", 7500, "http://img", 3, null, null, List.of("a", "b", "c", "d")))
                .isInstanceOf(ValidationException.class);
        verify(repository, never()).save(any());
    }
}
