package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.NewProductOption;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.ProductOption;
import com.shop.backend.domain.entity.ProductSort;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON shapes and request mapping of the product endpoints for options (V18). */
@WebMvcTest({ProductController.class, AdminProductController.class})
@Import({SecurityConfig.class, JwtService.class})
class ProductOptionsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private ProductService productService;

    private static ProductOption option(String id, String color, String hex, String size, int price, Integer original, int stock) {
        ProductOption o = new ProductOption("p1", new NewProductOption(color, hex, size, price, original, stock), 0);
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    private static Product product(String id, int price, boolean priceVaries) {
        Product p = new Product("티셔츠", "설명", price, "http://img/1", 0, null, null, null, priceVaries);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    // ---- detail ----

    @Test
    void 성공_상세는_옵션_배열과_priceVaries를_내려준다() throws Exception {
        Product p = product("p1", 9000, true);
        when(productService.getProduct("p1")).thenReturn(Optional.of(p));
        when(productService.getImageUrls(p)).thenReturn(List.of("http://img/1"));
        when(productService.getOptions(p)).thenReturn(List.of(
                option("o1", "화이트", "#FFFFFF", "M", 9000, 12000, 3), option("o2", "블랙", null, "L", 10000, null, 0)));

        mockMvc.perform(get("/api/products/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceVaries").value(true))
                .andExpect(jsonPath("$.options.length()").value(2))
                .andExpect(jsonPath("$.options[0].id").value("o1"))
                .andExpect(jsonPath("$.options[0].colorName").value("화이트"))
                .andExpect(jsonPath("$.options[0].colorHex").value("#FFFFFF"))
                .andExpect(jsonPath("$.options[0].sizeLabel").value("M"))
                .andExpect(jsonPath("$.options[0].price").value(9000))
                .andExpect(jsonPath("$.options[0].originalPrice").value(12000))
                .andExpect(jsonPath("$.options[0].discountRate").value(25))
                .andExpect(jsonPath("$.options[0].stock").value(3))
                .andExpect(jsonPath("$.options[1].colorHex").value(nullValue()))
                .andExpect(jsonPath("$.options[1].originalPrice").value(nullValue()))
                .andExpect(jsonPath("$.options[1].discountRate").value(nullValue()))
                .andExpect(jsonPath("$.options[1].stock").value(0))
                // existing fields are still there
                .andExpect(jsonPath("$.name").value("티셔츠"))
                .andExpect(jsonPath("$.price").value(9000))
                .andExpect(jsonPath("$.images[0]").value("http://img/1"));
    }

    @Test
    void 성공_옵션_없는_상품의_상세는_빈_옵션_배열과_priceVaries_false다() throws Exception {
        Product p = product("p1", 9000, false);
        when(productService.getProduct("p1")).thenReturn(Optional.of(p));
        when(productService.getImageUrls(p)).thenReturn(List.of("http://img/1"));
        when(productService.getOptions(p)).thenReturn(List.of());

        mockMvc.perform(get("/api/products/p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options").isArray())
                .andExpect(jsonPath("$.options.length()").value(0))
                .andExpect(jsonPath("$.priceVaries").value(false))
                .andExpect(jsonPath("$.stock").value(0));
    }

    @Test
    void 실패_없는_상품의_상세는_404다() throws Exception {
        when(productService.getProduct("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/products/nope")).andExpect(status().isNotFound());
    }

    // ---- list ----

    @Test
    void 성공_목록은_항목마다_priceVaries를_내려준다() throws Exception {
        when(productService.listProducts(isNull(), any(ProductSort.class), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(product("p1", 9000, true), product("p2", 5000, false)),
                        PageRequest.of(0, 8), 2));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].priceVaries").value(true))
                .andExpect(jsonPath("$.items[0].price").value(9000))
                .andExpect(jsonPath("$.items[1].priceVaries").value(false));
    }

    @Test
    void 성공_베스트_목록도_priceVaries를_내려준다() throws Exception {
        when(productService.listBestProducts(any(), anyInt())).thenReturn(List.of(product("p1", 9000, true)));

        mockMvc.perform(get("/api/products/best"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].priceVaries").value(true));
    }

    // ---- admin list ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_관리자_목록의_재고는_옵션_상품이면_옵션_재고_합계이고_아니면_상품_재고다() throws Exception {
        Product withOptions = product("p1", 9000, true);
        Product legacy = new Product("바지", "설명", 5000, "http://img/2", 7);
        ReflectionTestUtils.setField(legacy, "id", "p2");
        when(productService.listAdminProducts()).thenReturn(List.of(withOptions, legacy));
        when(productService.optionStockTotals()).thenReturn(Map.of("p1", 12L));

        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock").value(12))
                .andExpect(jsonPath("$[1].stock").value(7));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_옵션_재고_합계가_int_범위를_넘어도_int_최대값으로_자른다() throws Exception {
        when(productService.listAdminProducts()).thenReturn(List.of(product("p1", 9000, false)));
        when(productService.optionStockTotals()).thenReturn(Map.of("p1", 3_000_000_000L));

        mockMvc.perform(get("/api/admin/products"))
                .andExpect(jsonPath("$[0].stock").value(Integer.MAX_VALUE));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_옵션_재고_합계가_0이면_0이다() throws Exception {
        Product p = new Product("바지", "설명", 5000, "http://img/2", 7);
        ReflectionTestUtils.setField(p, "id", "p1");
        when(productService.listAdminProducts()).thenReturn(List.of(p));
        when(productService.optionStockTotals()).thenReturn(Map.of("p1", 0L));

        mockMvc.perform(get("/api/admin/products")).andExpect(jsonPath("$[0].stock").value(0));
    }

    // ---- admin create ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_옵션을_서비스에_순서대로_넘긴다() throws Exception {
        Product created = product("new-id", 5000, true);
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","imageUrls":["http://img/a"],
                 "options":[{"colorName":"화이트","colorHex":"#FFFFFF","sizeLabel":"M","price":5000,"originalPrice":8000,"stock":3},
                            {"sizeLabel":"L","price":6000,"stock":0}]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-id"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewProductOption>> options = ArgumentCaptor.forClass(List.class);
        verify(productService).createProduct(any(), any(), isNull(), isNull(), any(), isNull(), isNull(), isNull(), isNull(),
                options.capture());
        assertThat(options.getValue()).containsExactly(
                new NewProductOption("화이트", "#FFFFFF", "M", 5000, 8000, 3),
                new NewProductOption(null, null, "L", 6000, null, 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_options가_없으면_null로_넘긴다() throws Exception {
        Product created = product("new-id", 5000, false);
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        mockMvc.perform(post("/api/admin/products").contentType("application/json")
                        .content("{\"name\":\"n\",\"description\":\"d\",\"price\":5000,\"imageUrl\":\"http://img/a\",\"stock\":1}"))
                .andExpect(status().isOk());

        verify(productService).createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_서비스가_옵션을_거절하면_400과_한국어_메시지를_돌려준다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요."));

        String body = """
                {"name":"n","description":"d","imageUrl":"http://img/a","options":[{"sizeLabel":"M","price":1,"stock":1},{"sizeLabel":"m","price":1,"stock":1}]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("같은 색상과 사이즈 조합의 옵션이 중복되어 있어요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_옵션_목록에_null_항목이_있어도_서비스가_검증하고_500이_아니다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("옵션 정보가 올바르지 않아요."));

        mockMvc.perform(post("/api/admin/products").contentType("application/json")
                        .content("{\"name\":\"n\",\"description\":\"d\",\"imageUrl\":\"http://img/a\",\"options\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("옵션 정보가 올바르지 않아요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_options가_배열이_아니거나_가격이_숫자가_아니면_400이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(post("/api/admin/products").contentType("application/json")
                        .content("{\"name\":\"n\",\"description\":\"d\",\"options\":\"M\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/products").contentType("application/json")
                        .content("{\"name\":\"n\",\"description\":\"d\",\"options\":[{\"sizeLabel\":\"M\",\"price\":\"abc\",\"stock\":1}]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }
}
