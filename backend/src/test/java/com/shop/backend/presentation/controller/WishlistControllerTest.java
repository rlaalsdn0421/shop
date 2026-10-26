package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.WishlistService;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.ProductNotFoundException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.UserRepository;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WishlistController.class)
@Import({SecurityConfig.class, JwtService.class})
class WishlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private WishlistService wishlistService;

    private static Product product(String id, String name, int price, Integer originalPrice, List<String> tags) {
        Product p = new Product(name, "설명", price, "http://img/" + id, 5, "신발", originalPrice, tags);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    // ---- GET /api/wishlist/ids ----

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_ids는_서비스가_준_순서_그대로_ids_배열과_no_store로_응답한다() throws Exception {
        when(wishlistService.ids("user-1")).thenReturn(List.of("p3", "p1", "p2"));

        mockMvc.perform(get("/api/wishlist/ids"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.ids.length()").value(3))
                .andExpect(jsonPath("$.ids[0]").value("p3"))
                .andExpect(jsonPath("$.ids[1]").value("p1"))
                .andExpect(jsonPath("$.ids[2]").value("p2"));
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_찜이_없으면_ids는_null이_아니라_빈_배열이다() throws Exception {
        when(wishlistService.ids("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/wishlist/ids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ids").isArray())
                .andExpect(jsonPath("$.ids.length()").value(0));
    }

    // ---- GET /api/wishlist ----

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_목록은_순서를_유지하고_ProductListItem_모양으로_no_store로_응답한다() throws Exception {
        Product newest = product("p2", "최근 찜", 8000, 10000, List.of("#여름"));
        Product older = product("p1", "예전 찜", 5000, null, List.of());
        when(wishlistService.list("user-1")).thenReturn(List.of(newest, older));

        mockMvc.perform(get("/api/wishlist"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("p2"))
                .andExpect(jsonPath("$[0].name").value("최근 찜"))
                .andExpect(jsonPath("$[0].price").value(8000))
                .andExpect(jsonPath("$[0].imageUrl").value("http://img/p2"))
                .andExpect(jsonPath("$[0].originalPrice").value(10000))
                .andExpect(jsonPath("$[0].discountRate").value(20))
                .andExpect(jsonPath("$[0].hashtags[0]").value("#여름"))
                .andExpect(jsonPath("$[0].priceVaries").value(false))
                .andExpect(jsonPath("$[1].id").value("p1"))
                .andExpect(jsonPath("$[1].originalPrice").doesNotExist())
                // list item shape only: no detail-only fields
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].stock").doesNotExist());
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_찜이_없으면_목록은_빈_배열이다() throws Exception {
        when(wishlistService.list("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/wishlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- PUT / DELETE ----

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_PUT은_204와_no_store이고_인증된_사용자_id로_서비스를_부른다() throws Exception {
        mockMvc.perform(put("/api/wishlist/p1"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(wishlistService).add("user-1", "p1");
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_DELETE는_204와_no_store이고_인증된_사용자_id로_서비스를_부른다() throws Exception {
        mockMvc.perform(delete("/api/wishlist/p1"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verify(wishlistService).remove("user-1", "p1");
        verify(wishlistService, never()).add(anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 실패_없는_상품을_찜하면_404와_메시지다() throws Exception {
        doThrow(new ProductNotFoundException("nope")).when(wishlistService).add("user-1", "nope");

        mockMvc.perform(put("/api/wishlist/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("상품을 찾을 수 없습니다: nope"));
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 실패_한도를_넘기면_400과_안내_문구다() throws Exception {
        doThrow(new ValidationException("찜은 최대 200개까지 담을 수 있어요.")).when(wishlistService).add("user-1", "p1");

        mockMvc.perform(put("/api/wishlist/p1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("찜은 최대 200개까지 담을 수 있어요."));
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 실패_예상하지_못한_오류는_500이고_내부_메시지를_노출하지_않는다() throws Exception {
        when(wishlistService.ids("user-1")).thenThrow(new IllegalStateException("secret internal detail"));

        String body = mockMvc.perform(get("/api/wishlist/ids"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("secret internal detail");
    }

    // ---- authentication: anonymous gets 401 AUTH_REQUIRED on all four, service never called ----

    private MockHttpServletRequestBuilder[] allFour() {
        return new MockHttpServletRequestBuilder[]{
                get("/api/wishlist/ids"), get("/api/wishlist"), put("/api/wishlist/p1"), delete("/api/wishlist/p1")};
    }

    @Test
    void 실패_로그인하지_않으면_네_API_모두_401_AUTH_REQUIRED이고_서비스를_부르지_않는다() throws Exception {
        for (MockHttpServletRequestBuilder request : allFour()) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }

        verifyNoInteractions(wishlistService);
    }

    @Test
    void 실패_유효하지_않은_토큰이면_네_API_모두_401이고_서비스를_부르지_않는다() throws Exception {
        String[] tokens = {"Bearer garbage.token.value", "Bearer ", "Basic dXNlcjpwdw=="};
        for (String token : tokens) {
            for (MockHttpServletRequestBuilder request : allFour()) {
                mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
            }
        }

        verifyNoInteractions(wishlistService);
    }

    @Test
    void 실패_로그인하지_않으면_허용되지_않는_메서드도_401이다() throws Exception {
        mockMvc.perform(post("/api/wishlist/p1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/wishlist")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/wishlist")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/wishlist/")).andExpect(status().isUnauthorized());

        verifyNoInteractions(wishlistService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SELLER", "ADMIN"})
    void 성공_USER_SELLER_ADMIN_역할은_네_API를_모두_쓸_수_있다(String role) throws Exception {
        when(wishlistService.ids("someone")).thenReturn(List.of());
        when(wishlistService.list("someone")).thenReturn(List.of());
        org.springframework.security.core.authority.SimpleGrantedAuthority authority =
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role);
        var auth = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("someone").authorities(authority);

        mockMvc.perform(get("/api/wishlist/ids").with(auth)).andExpect(status().isOk());
        mockMvc.perform(get("/api/wishlist").with(auth)).andExpect(status().isOk());
        mockMvc.perform(put("/api/wishlist/p1").with(auth)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/wishlist/p1").with(auth)).andExpect(status().isNoContent());

        verify(wishlistService).add("someone", "p1");
        verify(wishlistService).remove("someone", "p1");
    }

    // ---- the user id comes only from the authenticated principal ----

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_요청의_쿼리_헤더_본문에_다른_사용자_id를_넣어도_서비스는_인증된_사용자_id만_받는다() throws Exception {
        String forged = "{\"userId\":\"victim-9\",\"user\":\"victim-9\"}";

        mockMvc.perform(put("/api/wishlist/p1?userId=victim-9&user=victim-9")
                        .header("X-User-Id", "victim-9").contentType("application/json").content(forged))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/wishlist/p1?userId=victim-9").header("X-User-Id", "victim-9"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/wishlist/ids?userId=victim-9").header("X-User-Id", "victim-9"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/wishlist?userId=victim-9").header("X-User-Id", "victim-9"))
                .andExpect(status().isOk());

        verify(wishlistService).add("user-1", "p1");
        verify(wishlistService).remove("user-1", "p1");
        verify(wishlistService).ids("user-1");
        verify(wishlistService).list("user-1");
        verify(wishlistService, never()).add(org.mockito.ArgumentMatchers.eq("victim-9"), anyString());
        verify(wishlistService, never()).remove(org.mockito.ArgumentMatchers.eq("victim-9"), anyString());
        verify(wishlistService, never()).ids("victim-9");
        verify(wishlistService, never()).list("victim-9");
    }

    @Test
    @WithMockUser(username = "user-2", roles = "USER")
    void 성공_다른_사용자로_로그인하면_그_사용자의_id가_넘어간다() throws Exception {
        mockMvc.perform(put("/api/wishlist/p1")).andExpect(status().isNoContent());

        verify(wishlistService).add("user-2", "p1");
        verify(wishlistService, never()).add(org.mockito.ArgumentMatchers.eq("user-1"), anyString());
    }

    // ---- odd path ids ----

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_경로의_상품_id는_공백과_한글과_긴_문자열도_그대로_서비스에_넘어간다() throws Exception {
        String longId = "x".repeat(100);

        mockMvc.perform(put("/api/wishlist/{id}", "a b")).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/wishlist/{id}", "상품-1")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/wishlist/{id}", longId)).andExpect(status().isNoContent());

        verify(wishlistService).add("user-1", "a b");
        verify(wishlistService).add("user-1", "상품-1");
        verify(wishlistService).remove("user-1", longId);
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 성공_PUT_ids는_ids_조회가_아니라_id가_ids인_상품을_찜하는_요청으로_처리된다() throws Exception {
        mockMvc.perform(put("/api/wishlist/ids")).andExpect(status().isNoContent());

        verify(wishlistService).add("user-1", "ids");
        verify(wishlistService, never()).ids(anyString());
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 실패_상품_id_없는_PUT_DELETE_그리고_POST는_4xx이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(put("/api/wishlist")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/wishlist")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/wishlist/p1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post("/api/wishlist/ids")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(wishlistService);
    }

    @Test
    @WithMockUser(username = "user-1", roles = "USER")
    void 실패_슬래시가_들어간_상품_id는_서비스에_닿지_않는다() throws Exception {
        // a/b is two path segments (no handler) and %2F is rejected by the firewall: neither may wish a product with a
        // slash in its id. The exact error status is not pinned (the global handler answers an unknown route with 500).
        for (String path : new String[]{"/api/wishlist/a/b", "/api/wishlist/a%2Fb"}) {
            int status = mockMvc.perform(put(path)).andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(status).as(path).isGreaterThanOrEqualTo(400);
        }

        verifyNoInteractions(wishlistService);
    }
}
