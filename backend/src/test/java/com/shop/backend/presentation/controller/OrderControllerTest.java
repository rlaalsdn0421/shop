package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.IdempotencyKeyReuseException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.security.JwtService;
import com.shop.backend.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtService.class})
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String KEY = "key-12345";

    @MockBean
    private OrderService orderService;

    @Test
    @WithMockUser
    void 성공_주문을_생성한다() throws Exception {
        Order order = new Order("user-1", "key-12345", "hash", "홍길동", "010-1234-5678", "서울시", 10000);
        ReflectionTestUtils.setField(order, "id", "order-1");
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenReturn(order);

        String body = """
                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                 "items":[{"productId":"p1","quantity":2}]}
                """;

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", KEY)
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
                        .header("Idempotency-Key", KEY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void 실패_수량이_올바르지_않으면_400을_반환한다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("수량이 올바르지 않습니다."));

        String body = """
                {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
                 "items":[{"productId":"p1","quantity":0}]}
                """;

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", KEY)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("수량이 올바르지 않습니다."));
    }

    private static final String ORDER_BODY = """
            {"customerName":"홍길동","customerPhone":"010-1234-5678","customerAddress":"서울시",
             "items":[{"productId":"p1","quantity":1}]}
            """;

    @Test
    @WithMockUser
    void 실패_행_잠금_대기_시간이_지나면_503과_Retry_After를_반환한다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenThrow(new CannotAcquireLockException("lock timeout"));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", KEY).contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("주문이 몰려 처리하지 못했어요. 잠시 후 다시 시도해 주세요."));
    }

    @Test
    @WithMockUser
    void 실패_교착_상태로_밀려난_주문도_503과_Retry_After를_반환한다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenThrow(new DeadlockLoserDataAccessException("deadlock", null));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", KEY).contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }

    @Test
    @WithMockUser
    void 실패_잠금과_관계없는_DB_오류는_여전히_500이다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("db down"));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", KEY).contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_로그인_사용자의_id와_요청_번호를_서비스에_넘긴다() throws Exception {
        Order order = new Order("user-1", KEY, "hash", "홍길동", "010-1234-5678", "서울시", 10000);
        ReflectionTestUtils.setField(order, "id", "order-1");
        when(orderService.createOrder(eq("user-1"), eq(KEY), any(), any(), any(), any())).thenReturn(order);

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", KEY).contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("order-1"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_요청_번호_헤더가_없으면_서비스의_한국어_400을_그대로_반환한다() throws Exception {
        when(orderService.createOrder(eq("user-1"), isNull(), any(), any(), any(), any()))
                .thenThrow(new ValidationException("요청 번호(Idempotency-Key)가 올바르지 않아요."));

        mockMvc.perform(post("/api/orders").contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 번호(Idempotency-Key)가 올바르지 않아요."));
    }

    @Test
    @WithMockUser
    void 실패_같은_요청_번호로_다른_내용이면_409를_반환한다() throws Exception {
        when(orderService.createOrder(any(), any(), any(), any(), any(), any())).thenThrow(new IdempotencyKeyReuseException());

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", KEY).contentType("application/json").content(ORDER_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("같은 요청 번호로 내용이 다른 주문은 보낼 수 없어요."));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_주문상세를_조회한다() throws Exception {
        Product product = new Product("티셔츠", "설명", 5000, "http://img/1", 5);
        Order order = new Order("user-1", "key-12345", "hash", "홍길동", "010-1234-5678", "서울시", 10000);
        OrderItem item = new OrderItem(product, 2, 5000);
        order.addItem(item);
        ReflectionTestUtils.setField(order, "id", "order-1");
        ReflectionTestUtils.setField(item, "id", "item-1");
        when(orderService.getOrder("order-1", "user-1")).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(10000))
                .andExpect(jsonPath("$.customerName").value("홍길동"))
                .andExpect(jsonPath("$.items[0].productName").value("티셔츠"))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_존재하지_않거나_남의_주문이면_404를_반환한다() throws Exception {
        when(orderService.getOrder("missing", "user-1")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/orders/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 실패_인증없이_주문을_조회하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders/order-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 실패_인증없이_주문_목록을_조회하면_401을_반환하고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_내_주문_목록을_응답_형태대로_반환한다() throws Exception {
        Order order = new Order("user-1", KEY, "hash", "홍길동", "010-1234-5678", "서울시", 10000);
        ReflectionTestUtils.setField(order, "id", "order-1");
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-10-11T01:02:03.123456Z"));
        when(orderService.listMyOrders("user-1", 10, null)).thenReturn(new OrderService.OrderPage(
                List.of(new OrderService.OrderSummary(order, 3, "티셔츠")), "next-cursor"));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].id").value("order-1"))
                .andExpect(jsonPath("$.orders[0].createdAt").value("2026-10-11T01:02:03.123456Z"))
                .andExpect(jsonPath("$.orders[0].totalAmount").value(10000))
                .andExpect(jsonPath("$.orders[0].status").value("PAID"))
                .andExpect(jsonPath("$.orders[0].itemCount").value(3))
                .andExpect(jsonPath("$.orders[0].firstItemName").value("티셔츠"))
                .andExpect(jsonPath("$.orders[0].customerAddress").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_size와_cursor를_서비스에_넘기고_마지막_페이지는_nextCursor가_null이다() throws Exception {
        when(orderService.listMyOrders("user-1", 25, "abc")).thenReturn(new OrderService.OrderPage(List.of(), null));

        mockMvc.perform(get("/api/orders").param("size", "25").param("cursor", "abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_size가_숫자가_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/orders").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("요청 값의 형식이 올바르지 않아요."));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_size가_범위_밖이면_서비스의_한국어_400을_그대로_반환한다() throws Exception {
        when(orderService.listMyOrders("user-1", 51, null))
                .thenThrow(new ValidationException("주문 수는 1~50개 사이여야 해요."));

        mockMvc.perform(get("/api/orders").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("주문 수는 1~50개 사이여야 해요."));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_커서가_올바르지_않으면_한국어_400을_반환한다() throws Exception {
        when(orderService.listMyOrders("user-1", 10, "garbage"))
                .thenThrow(new ValidationException("커서가 올바르지 않아요."));

        mockMvc.perform(get("/api/orders").param("cursor", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("커서가 올바르지 않아요."));
    }
}
