package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
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
        when(productService.listProducts(null)).thenReturn(List.of(p1, p2));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("티셔츠"))
                .andExpect(jsonPath("$[0].price").value(10000))
                .andExpect(jsonPath("$[0].imageUrl").value("http://img/1"));
    }

    @Test
    void 성공_카테고리로_필터링한_상품목록을_반환한다() throws Exception {
        Product p1 = new Product("축구화", "설명", 30000, "http://img/3", 2, "스포츠/레저");
        when(productService.listProducts(eq("스포츠/레저"))).thenReturn(List.of(p1));

        mockMvc.perform(get("/api/products").param("category", "스포츠/레저"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
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
}
