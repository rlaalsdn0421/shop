package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProductController.class)
@Import({SecurityConfig.class, JwtService.class})
class AdminProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_관리자가_상품목록을_조회한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 10000, "http://img/1", 5);
        when(productService.listAdminProducts()).thenReturn(List.of(product));

        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 실패_인증없이_요청하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void 실패_권한이_없으면_403을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_판매자가_상품을_등록한다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/new", 10, "스포츠/레저");
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,"category":"스포츠/레저"}
                """;

        mockMvc.perform(post("/api/admin/products")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-id"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_유효성검증에_실패하면_400을_반환한다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("이름, 설명, 이미지 URL을 올바르게 입력해주세요."));

        String body = """
                {"name":"","description":"","price":0,"imageUrl":"","stock":0,"category":null}
                """;

        mockMvc.perform(post("/api/admin/products")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이름, 설명, 이미지 URL을 올바르게 입력해주세요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_정가와_해시태그를_서비스에_그대로_넘긴다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/new", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,
                 "originalPrice":8000,"hashtags":["#여름","세일"]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
        verify(productService).createProduct(eq("새상품"), eq("설명"), eq(5000), eq("http://img/new"), eq(10),
                isNull(), eq(8000), tags.capture());
        assertThat(tags.getValue()).containsExactly("#여름", "세일");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_정가와_해시태그가_없으면_null로_넘긴다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/new", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk());

        verify(productService).createProduct(any(), any(), any(), any(), any(), any(), isNull(), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_정가가_판매가_이하면_400을_반환한다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("정가는 판매가보다 커야 해요."));

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,"originalPrice":5000}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("정가는 판매가보다 커야 해요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_해시태그가_4개면_400을_반환한다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("해시태그는 최대 3개까지 입력할 수 있어요."));

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,
                 "hashtags":["a","b","c","d"]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("해시태그는 최대 3개까지 입력할 수 있어요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_해시태그가_배열이_아니면_400을_반환하고_서비스를_부르지_않는다() throws Exception {
        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,"hashtags":"여름"}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }
}
