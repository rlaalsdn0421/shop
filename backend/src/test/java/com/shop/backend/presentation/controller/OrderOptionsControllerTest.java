package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.OrderLine;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.InsufficientStockException;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Order request mapping and the optionLabel in the order views (V18). */
@WebMvcTest({OrderController.class, AdminOrderController.class})
@Import({SecurityConfig.class, JwtService.class})
class OrderOptionsControllerTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private OrderService orderService;

    @MockBean
    private OrderCancelService orderCancelService;

    private static Order orderWithItems(OrderItem... items) {
        Order order = new Order("user-1", "key-12345", "hash", "홍길동", "010-1234-5678", "서울시", 13_500, 3_500, NOW);
        ReflectionTestUtils.setField(order, "id", "order-1");
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return order;
    }

    private static OrderItem item(String id, String name, int price, String optionId, String optionLabel) {
        Product product = new Product(name, "설명", price, "http://img/x", 5);
        ReflectionTestUtils.setField(product, "id", "p-" + id);
        OrderItem item = new OrderItem(product, 1, price, optionId, optionLabel);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    // ---- request ----

    @Test
    @WithMockUser
    void 성공_주문_줄의_optionId를_서비스에_넘기고_없으면_null이다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenReturn(orderWithItems());

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-12345").contentType("application/json")
                        .content("""
                                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                                 "items":[{"productId":"p1","quantity":2,"optionId":"o1"},{"productId":"p2","quantity":1}]}
                                """))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OrderLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(orderService).createOrder(any(), eq("key-12345"), any(), any(), any(), lines.capture());
        assertThat(lines.getValue()).containsExactly(new OrderLine("p1", 2, "o1"), new OrderLine("p2", 1, null));
    }

    @Test
    @WithMockUser
    void 실패_서비스가_옵션_선택을_요구하면_400과_메시지를_돌려준다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("옵션을 선택해 주세요."));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-12345").contentType("application/json")
                        .content("""
                                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                                 "items":[{"productId":"p1","quantity":2}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("옵션을 선택해 주세요."));
    }

    @Test
    @WithMockUser
    void 실패_서비스가_없는_옵션을_거절하면_400과_메시지를_돌려준다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("선택한 옵션을 찾을 수 없어요."));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-12345").contentType("application/json")
                        .content("""
                                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                                 "items":[{"productId":"p1","quantity":2,"optionId":"zzz"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("선택한 옵션을 찾을 수 없어요."));
    }

    @Test
    @WithMockUser
    void 실패_옵션_재고가_모자라면_400이고_옵션_이름이_메시지에_들어_있다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new InsufficientStockException("티셔츠 (화이트 / M)"));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-12345").contentType("application/json")
                        .content("""
                                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                                 "items":[{"productId":"p1","quantity":2,"optionId":"o1"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("재고가 부족합니다: 티셔츠 (화이트 / M)"));
    }

    // ---- views ----

    @Test
    @WithMockUser
    void 성공_주문_상세의_항목은_저장된_가격과_optionLabel을_내려준다() throws Exception {
        Order order = orderWithItems(item("i1", "티셔츠", 9000, "o1", "화이트 / M"), item("i2", "바지", 5000, null, null));
        when(orderService.getOrder(any(), any())).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].optionLabel").value("화이트 / M"))
                .andExpect(jsonPath("$.items[0].price").value(9000))
                .andExpect(jsonPath("$.items[0].productName").value("티셔츠"))
                .andExpect(jsonPath("$.items[1].optionLabel").value(nullValue()))
                .andExpect(jsonPath("$.items[1].price").value(5000));
    }

    @Test
    @WithMockUser
    void 성공_내_주문_목록은_대표_항목의_firstItemOptionLabel을_내려준다() throws Exception {
        Order order = orderWithItems();
        when(orderService.listMyOrders(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(
                new OrderService.OrderPage(List.of(new OrderService.OrderSummary(order, 2, "티셔츠", "화이트 / M")), null));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].firstItemName").value("티셔츠"))
                .andExpect(jsonPath("$.orders[0].firstItemOptionLabel").value("화이트 / M"));
    }

    @Test
    @WithMockUser
    void 성공_옵션_없는_주문_목록의_firstItemOptionLabel은_null이다() throws Exception {
        Order order = orderWithItems();
        when(orderService.listMyOrders(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(
                new OrderService.OrderPage(List.of(new OrderService.OrderSummary(order, 1, "티셔츠")), null));

        mockMvc.perform(get("/api/orders"))
                .andExpect(jsonPath("$.orders[0].firstItemOptionLabel").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_관리자_주문_목록도_firstItemOptionLabel을_내려준다() throws Exception {
        Order order = orderWithItems();
        when(orderService.listAdminOrders(any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(
                new OrderService.OrderPage(List.of(new OrderService.OrderSummary(order, 2, "티셔츠", "블랙 / L")), null));

        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].firstItemOptionLabel").value("블랙 / L"))
                .andExpect(jsonPath("$.orders[0].firstItemName").value("티셔츠"));
    }
}
