package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtService.class})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    @WithMockUser
    void 성공_주문을_생성한다() throws Exception {
        Order order = new Order("홍길동", "010-1234-5678", "서울시", 10000);
        ReflectionTestUtils.setField(order, "id", "order-1");
        when(orderService.createOrder(any(), any(), any(), any())).thenReturn(order);

        String body = """
                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                 "items":[{"productId":"p1","quantity":2}]}
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"));
    }

    @Test
    void 실패_인증없이_주문하면_401을_반환한다() throws Exception {
        String body = """
                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                 "items":[{"productId":"p1","quantity":2}]}
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void 실패_수량이_올바르지_않으면_400을_반환한다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any()))
                .thenThrow(new ValidationException("수량이 올바르지 않습니다."));

        String body = """
                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                 "items":[{"productId":"p1","quantity":0}]}
                """;

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("수량이 올바르지 않습니다."));
    }

    @Test
    void 성공_주문상세를_조회한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 5000, "http://img/1", 5);
        Order order = new Order("홍길동", "010-1234-5678", "서울시", 10000);
        OrderItem item = new OrderItem(product, 2, 5000);
        order.addItem(item);
        ReflectionTestUtils.setField(order, "id", "order-1");
        ReflectionTestUtils.setField(item, "id", "item-1");
        when(orderService.getOrder("order-1")).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(10000))
                .andExpect(jsonPath("$.customerName").value("홍길동"))
                .andExpect(jsonPath("$.items[0].productName").value("티셔츠"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void 실패_존재하지_않는_주문이면_404를_반환한다() throws Exception {
        when(orderService.getOrder("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/orders/missing"))
                .andExpect(status().isNotFound());
    }
}
