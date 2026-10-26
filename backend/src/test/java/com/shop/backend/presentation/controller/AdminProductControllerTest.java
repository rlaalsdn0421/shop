package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.ProductService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.entity.OptionEdit;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.StockChangedException;
import com.shop.backend.domain.error.ValidationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import com.shop.backend.infrastructure.repository.UserRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminProductController.class)
@Import({SecurityConfig.class, JwtService.class})
class AdminProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 실패_권한이_없으면_403을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_판매자가_상품을_등록한다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/new", 10, "스포츠/레저");
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

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
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10,
                 "originalPrice":8000,"hashtags":["#여름","세일"]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
        verify(productService).createProduct(eq("새상품"), eq("설명"), eq(5000), eq("http://img/new"), isNull(), eq(10),
                isNull(), eq(8000), tags.capture(), isNull());
        assertThat(tags.getValue()).containsExactly("#여름", "세일");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_정가와_해시태그가_없으면_null로_넘긴다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/new", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk());

        verify(productService).createProduct(any(), any(), any(), any(), isNull(), any(), any(), isNull(), isNull(), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_imageUrls를_서비스에_순서대로_넘긴다() throws Exception {
        Product created = new Product("새상품", "설명", 5000, "http://img/a", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrls":["http://img/a","http://img/b"],"stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("new-id"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> urls = ArgumentCaptor.forClass(List.class);
        verify(productService).createProduct(any(), any(), any(), isNull(), urls.capture(), any(), any(), any(), any(), any());
        assertThat(urls.getValue()).containsExactly("http://img/a", "http://img/b");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_서비스가_이미지를_거절하면_400을_반환한다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("이미지는 1~10장까지 등록할 수 있어요."));

        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrls":["http://img/a"],"stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미지는 1~10장까지 등록할 수 있어요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_imageUrls가_배열이_아니면_400을_반환하고_서비스를_부르지_않는다() throws Exception {
        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrls":"http://img/a","stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_정가가_판매가_이하면_400을_반환한다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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

    // ---- PUT /api/admin/products/{id} (edit) ----

    private static final String PLAIN_EDIT = """
            {"name":"새이름","description":"새설명","category":"신발","hashtags":["a","b"],
             "imageUrls":["https://img.example.com/a.png","https://img.example.com/b.png"],
             "price":12000,"originalPrice":15000,"stock":8,"expectedStock":10}
            """;

    private static Product edited(String id) {
        Product product = new Product("새이름", "새설명", 12000, "https://img.example.com/a.png", 8);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private void stubUpdateThrows(Throwable error) {
        // doThrow, not when(): a second stubbing must not call the already-throwing mock
        doThrow(error).when(productService).updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_관리자가_상품을_수정하면_id를_돌려주고_본문_값을_서비스에_그대로_넘긴다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p1"));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> urls = ArgumentCaptor.forClass(List.class);
        verify(productService).updateProduct(eq("p1"), eq("새이름"), eq("새설명"), eq("신발"), tags.capture(), urls.capture(),
                eq(12000), eq(15000), eq(8), eq(10), isNull(), any());
        assertThat(tags.getValue()).containsExactly("a", "b");
        assertThat(urls.getValue()).containsExactly("https://img.example.com/a.png", "https://img.example.com/b.png");
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_판매자도_옵션_상품을_수정할_수_있고_옵션_값이_넘어간다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p2"));
        String body = """
                {"name":"새이름","description":"새설명","imageUrls":["https://img.example.com/a.png"],
                 "options":[{"id":"o1","price":1000,"originalPrice":1500,"stock":3,"expectedStock":5},
                            {"id":"o2","price":2000,"stock":0,"expectedStock":0}]}
                """;

        mockMvc.perform(put("/api/admin/products/p2").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p2"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OptionEdit>> options = ArgumentCaptor.forClass(List.class);
        verify(productService).updateProduct(eq("p2"), any(), any(), isNull(), isNull(), any(), isNull(), isNull(), isNull(),
                isNull(), options.capture(), any());
        assertThat(options.getValue()).containsExactly(new OptionEdit("o1", 1000, 1500, 3, 5),
                new OptionEdit("o2", 2000, null, 0, 0));
    }

    @Test
    void 실패_인증없이_수정하면_401이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void 실패_일반_회원이_수정하면_403이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isForbidden());

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_검증_오류는_400이다() throws Exception {
        stubUpdateThrows(new ValidationException("옵션 가격이 올바르지 않습니다."));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("옵션 가격이 올바르지 않습니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_없는_상품은_404이다() throws Exception {
        stubUpdateThrows(new ProductNotFoundException("nope"));

        mockMvc.perform(put("/api/admin/products/nope").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_재고가_그_사이_바뀌었으면_409이고_STOCK_CHANGED_코드와_안내_문구를_준다() throws Exception {
        stubUpdateThrows(new StockChangedException());

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_CHANGED"))
                .andExpect(jsonPath("$.error").value("그 사이 재고가 바뀌었어요. 새로고침한 뒤 다시 입력해 주세요."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_행_잠금_시간_초과와_교착은_503과_Retry_After이고_내부_정보를_노출하지_않는다() throws Exception {
        stubUpdateThrows(new CannotAcquireLockException("could not obtain lock on row in relation \"products\""));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해 주세요."));

        stubUpdateThrows(new PessimisticLockingFailureException("deadlock detected"));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_본문이_깨진_JSON이면_400이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content("{\"name\":"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_숫자_필드에_문자열을_보내면_400이고_서비스를_부르지_않는다() throws Exception {
        String body = """
                {"name":"새이름","description":"새설명","imageUrls":["https://img.example.com/a.png"],
                 "price":"abc","stock":8,"expectedStock":10}
                """;

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_옵션_목록이_배열이_아니면_400이고_서비스를_부르지_않는다() throws Exception {
        String body = """
                {"name":"새이름","description":"새설명","imageUrls":["https://img.example.com/a.png"],"options":"x"}
                """;

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_옵션_배열에_null_항목이_있으면_null로_서비스에_넘어가_검증_오류_400이_된다() throws Exception {
        stubUpdateThrows(new ValidationException("옵션 정보가 올바르지 않아요."));
        String body = """
                {"name":"새이름","description":"새설명","imageUrls":["https://img.example.com/a.png"],"options":[null]}
                """;

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("옵션 정보가 올바르지 않아요."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OptionEdit>> options = ArgumentCaptor.forClass(List.class);
        verify(productService).updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), options.capture(), any());
        assertThat(options.getValue()).containsExactly((OptionEdit) null);
    }

    @Test
    @WithMockUser(username = "seller-7", roles = "SELLER")
    void 성공_수정한_사람은_로그인한_판매자의_이름으로_서비스에_넘어간다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p1"));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isOk());

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(productService).updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue()).isEqualTo("seller-7");
    }

    @Test
    @WithMockUser(username = "admin-9", roles = "ADMIN")
    void 성공_수정한_사람은_로그인한_관리자의_이름으로_서비스에_넘어간다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p1"));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isOk());

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(productService).updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), actor.capture());
        assertThat(actor.getValue()).isEqualTo("admin-9");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_수정_중_예상하지_못한_오류는_500이고_수정_문구이며_내부_메시지를_노출하지_않는다() throws Exception {
        stubUpdateThrows(new IllegalStateException("secret internal detail"));

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(PLAIN_EDIT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("상품 수정 중 오류가 발생했습니다."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_등록_중_예상하지_못한_오류는_500이고_등록_문구가_그대로다() throws Exception {
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("secret internal detail"));
        String body = """
                {"name":"새상품","description":"설명","price":5000,"imageUrl":"http://img/new","stock":10}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("상품 등록 중 오류가 발생했습니다."));
    }

    // ---- category regression: every request field must reach ITS parameter of the service ----

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_등록_요청의_카테고리와_나머지_필드가_서비스의_올바른_위치로_넘어간다() throws Exception {
        Product created = new Product("이름값", "설명값", 5000, "http://img/new", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        String body = """
                {"name":"이름값","description":"설명값","price":5000,"imageUrl":"http://img/new","stock":10,
                 "category":"스포츠/레저","originalPrice":8000,"hashtags":["#여름"]}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(body))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tags = ArgumentCaptor.forClass(List.class);
        // order: name, description, price, imageUrl, imageUrls, stock, category, originalPrice, hashtags, options
        verify(productService).createProduct(eq("이름값"), eq("설명값"), eq(5000), eq("http://img/new"), isNull(), eq(10),
                eq("스포츠/레저"), eq(8000), tags.capture(), isNull());
        assertThat(tags.getValue()).containsExactly("#여름");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_등록_요청에_카테고리가_없거나_비어_있으면_그_값_그대로_넘기고_다른_필드로_새지_않는다() throws Exception {
        Product created = new Product("이름값", "설명값", 5000, "http://img/new", 10);
        ReflectionTestUtils.setField(created, "id", "new-id");
        when(productService.createProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(created);
        String omitted = """
                {"name":"이름값","description":"설명값","price":5000,"imageUrl":"http://img/new","stock":10}
                """;
        String blank = """
                {"name":"이름값","description":"설명값","price":5000,"imageUrl":"http://img/new","stock":10,"category":"  "}
                """;

        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(omitted)).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/products").contentType("application/json").content(blank)).andExpect(status().isOk());

        // the service normalizes blank to NULL; the controller must not invent or move a category
        verify(productService).createProduct(eq("이름값"), eq("설명값"), eq(5000), eq("http://img/new"), isNull(), eq(10),
                isNull(), isNull(), isNull(), isNull());
        verify(productService).createProduct(eq("이름값"), eq("설명값"), eq(5000), eq("http://img/new"), isNull(), eq(10),
                eq("  "), isNull(), isNull(), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_수정_요청의_카테고리와_나머지_필드가_서비스의_올바른_위치로_넘어간다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p1"));
        String body = """
                {"name":"이름값","description":"설명값","category":"스포츠/레저","hashtags":["x"],
                 "imageUrls":["https://img.example.com/a.png"],"price":12000,"originalPrice":15000,"stock":8,"expectedStock":10}
                """;

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(body))
                .andExpect(status().isOk());

        // order: id, name, description, category, hashtags, imageUrls, price, originalPrice, stock, expectedStock, options, actor
        verify(productService).updateProduct(eq("p1"), eq("이름값"), eq("설명값"), eq("스포츠/레저"), any(), any(),
                eq(12000), eq(15000), eq(8), eq(10), isNull(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_수정_요청에_카테고리가_없으면_null로_넘기고_설명으로_대신_넘기지_않는다() throws Exception {
        when(productService.updateProduct(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(edited("p1"));
        String body = """
                {"name":"이름값","description":"설명값","imageUrls":["https://img.example.com/a.png"],
                 "price":12000,"stock":8,"expectedStock":10}
                """;

        mockMvc.perform(put("/api/admin/products/p1").contentType("application/json").content(body))
                .andExpect(status().isOk());

        verify(productService).updateProduct(eq("p1"), eq("이름값"), eq("설명값"), isNull(), isNull(), any(),
                eq(12000), isNull(), eq(8), eq(10), isNull(), any());
    }
}
