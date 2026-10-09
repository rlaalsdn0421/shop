package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.BestPeriod;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, JwtService.class})
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    void 성공_상품목록을_반환한다() throws Exception {
        Product p1 = new Product("티셔츠", "설명1", 10000, "http://img/1", 5);
        Product p2 = new Product("바지", "설명2", 20000, "http://img/2", 3);
        when(productService.listProducts(isNull(), any(ProductSort.class), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(p1, p2), PageRequest.of(0, 8), 2));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("티셔츠"))
                .andExpect(jsonPath("$.items[0].price").value(10000))
                .andExpect(jsonPath("$.items[0].imageUrl").value("http://img/1"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void 성공_카테고리로_필터링한_상품목록을_반환한다() throws Exception {
        Product p1 = new Product("축구화", "설명", 30000, "http://img/3", 2, "스포츠/레저");
        when(productService.listProducts(eq("스포츠/레저"), any(ProductSort.class), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(p1), PageRequest.of(0, 8), 1));

        mockMvc.perform(get("/api/products").param("category", "스포츠/레저"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void 성공_다음_페이지가_있으면_hasMore가_true다() throws Exception {
        Product p1 = new Product("티셔츠", "설명1", 10000, "http://img/1", 5);
        when(productService.listProducts(isNull(), any(ProductSort.class), eq(0), eq(1)))
                .thenReturn(new PageImpl<>(List.of(p1), PageRequest.of(0, 1), 2));

        mockMvc.perform(get("/api/products").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void 성공_상품상세를_반환한다() throws Exception {
        Product product = new Product("티셔츠", "상세설명", 10000, "http://img/1", 5);
        when(productService.getProduct("id1")).thenReturn(Optional.of(product));

        mockMvc.perform(get("/api/products/id1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("티셔츠"))
                .andExpect(jsonPath("$.price").value(10000))
                .andExpect(jsonPath("$.imageUrl").value("http://img/1"))
                .andExpect(jsonPath("$.description").value("상세설명"))
                .andExpect(jsonPath("$.stock").value(5));
    }

    @Test
    void 실패_존재하지_않는_상품이면_404를_반환한다() throws Exception {
        when(productService.getProduct("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/products/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    // ---- sort / paging ----

    @ParameterizedTest
    @CsvSource({"newest,NEWEST", "price_asc,PRICE_ASC", "price_desc,PRICE_DESC", "reviews,REVIEWS",
            "rating,RATING", "sales,SALES", "popular,POPULAR"})
    void 성공_sort_값을_서비스에_정렬로_넘긴다(String param, ProductSort expected) throws Exception {
        when(productService.listProducts(null, expected, 0, 8))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 8), 0));

        mockMvc.perform(get("/api/products").param("sort", param))
                .andExpect(status().isOk());

        verify(productService).listProducts(null, expected, 0, 8);
    }

    @Test
    void 성공_sort가_없으면_최신순이다() throws Exception {
        when(productService.listProducts(null, ProductSort.NEWEST, 0, 8))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 8), 0));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk());

        verify(productService).listProducts(null, ProductSort.NEWEST, 0, 8);
    }

    @Test
    void 성공_정렬은_카테고리_페이지_크기와_함께_쓸_수_있다() throws Exception {
        when(productService.listProducts("신발", ProductSort.RATING, 2, 50))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        mockMvc.perform(get("/api/products").param("category", "신발").param("sort", "rating")
                        .param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        verify(productService).listProducts("신발", ProductSort.RATING, 2, 50);
    }

    @Test
    void 실패_알_수_없는_sort면_400이다() throws Exception {
        mockMvc.perform(get("/api/products").param("sort", "cheapest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("지원하지 않는 정렬이에요."));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "51"})
    void 실패_size가_범위를_벗어나면_400이다(String size) throws Exception {
        mockMvc.perform(get("/api/products").param("size", size))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    void 실패_page가_음수면_400이다() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "-1"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    // ---- best ----

    @Test
    void 성공_베스트상품은_items만_담아_반환한다() throws Exception {
        Product p1 = new Product("티셔츠", "설명1", 10000, "http://img/1", 5);
        when(productService.listBestProducts(BestPeriod.WEEKLY, 4)).thenReturn(List.of(p1));

        mockMvc.perform(get("/api/products/best").param("period", "weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("티셔츠"))
                .andExpect(jsonPath("$.items[0].price").value(10000))
                .andExpect(jsonPath("$.items[0].imageUrl").value("http://img/1"))
                .andExpect(jsonPath("$.hasMore").doesNotExist());
    }

    @Test
    void 성공_period가_없으면_realtime_size는_4다() throws Exception {
        when(productService.listBestProducts(BestPeriod.REALTIME, 4)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/best"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        verify(productService).listBestProducts(BestPeriod.REALTIME, 4);
    }

    @ParameterizedTest
    @CsvSource({"realtime,REALTIME", "weekly,WEEKLY", "monthly,MONTHLY"})
    void 성공_period_값을_서비스에_넘긴다(String param, BestPeriod expected) throws Exception {
        when(productService.listBestProducts(expected, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/best").param("period", param).param("size", "20"))
                .andExpect(status().isOk());

        verify(productService).listBestProducts(expected, 20);
    }

    @Test
    void 실패_알_수_없는_period면_400이다() throws Exception {
        mockMvc.perform(get("/api/products/best").param("period", "yearly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("지원하지 않는 기간이에요."));

        verifyNoInteractions(productService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "21"})
    void 실패_베스트_size가_범위를_벗어나면_400이다(String size) throws Exception {
        mockMvc.perform(get("/api/products/best").param("size", size))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    // ---- bounds / malformed numbers ----

    @Test
    void 성공_size_경계값_1과_50은_통과한다() throws Exception {
        when(productService.listProducts(null, ProductSort.NEWEST, 0, 1))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(productService.listProducts(null, ProductSort.NEWEST, 0, 50))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/products").param("size", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products").param("size", "50")).andExpect(status().isOk());
    }

    @Test
    void 성공_page_곱하기_size가_int_최대값_이하면_통과한다() throws Exception {
        when(productService.listProducts(null, ProductSort.NEWEST, 42949672, 50))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        mockMvc.perform(get("/api/products").param("page", "42949672").param("size", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void 실패_page_곱하기_size가_int_최대값을_넘으면_400이다() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "42949673").param("size", "50"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("페이지 번호가 너무 커요."));

        verifyNoInteractions(productService);
    }

    @Test
    void 실패_page가_숫자가_아니면_400이고_입력값을_돌려주지_않는다() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 값의 형식이 올바르지 않아요."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("abc"))));

        verifyNoInteractions(productService);
    }

    @Test
    void 실패_size가_숫자가_아니면_400이다() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "x"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    void 실패_page가_int_범위를_넘는_숫자면_400이다() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "99999999999"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    void 실패_베스트_size가_숫자가_아니면_400이다() throws Exception {
        mockMvc.perform(get("/api/products/best").param("size", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 값의 형식이 올바르지 않아요."));

        verifyNoInteractions(productService);
    }

    @Test
    void 성공_베스트_size_경계값_1과_20은_통과한다() throws Exception {
        when(productService.listBestProducts(BestPeriod.REALTIME, 1)).thenReturn(List.of());
        when(productService.listBestProducts(BestPeriod.REALTIME, 20)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/best").param("size", "1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/best").param("size", "20")).andExpect(status().isOk());
    }
}
