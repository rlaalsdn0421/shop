package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderItem;
import com.shop.backend.domain.entity.Product;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.ValidationException;
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

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/orders/{id}/cancel and the new status fields of the order views. */
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtService.class})
class OrderControllerCancelTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private OrderService orderService;
    @MockBean
    private OrderCancelService orderCancelService;

    private static final String CANCEL = "/api/orders/o1/cancel";

    @Test
    @WithMockUser(username = "user-1")
    void 성공_주문자가_사유와_함께_취소하면_새_상태를_돌려준다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", "단순 변심")).thenReturn(Order.STATUS_CANCELLED);

        mockMvc.perform(post(CANCEL).contentType("application/json").content("{\"reason\":\"단순 변심\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_본문_없이도_취소할_수_있다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", null)).thenReturn(Order.STATUS_CANCELLED);

        mockMvc.perform(post(CANCEL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(orderCancelService).cancelByCustomer(eq("o1"), eq("user-1"), isNull());
    }

    @Test
    void 실패_로그인_없이_취소하면_401이다() throws Exception {
        mockMvc.perform(post(CANCEL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(orderCancelService);
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_남의_주문이거나_없는_주문이면_404다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", null)).thenThrow(new OrderNotFoundException());

        mockMvc.perform(post(CANCEL))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_배송이_시작된_주문은_409_ORDER_NOT_CANCELLABLE이다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", null))
                .thenThrow(new OrderNotCancellableException("배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요."));

        mockMvc.perform(post(CANCEL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.error").value("배송이 시작되어 바로 취소할 수 없어요. 교환·환불은 연락처로 신청해 주세요."));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_포트원_취소가_안_되면_503_CANCEL_PROVIDER_UNAVAILABLE이다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", null)).thenThrow(new CancelProviderUnavailableException(false));

        mockMvc.perform(post(CANCEL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_UNAVAILABLE"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_결제_설정이_없으면_503_PAYMENT_NOT_CONFIGURED이다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", null)).thenThrow(new PaymentNotConfiguredException());

        mockMvc.perform(post(CANCEL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_사유가_너무_길면_400이다() throws Exception {
        when(orderCancelService.cancelByCustomer("o1", "user-1", "a".repeat(201)))
                .thenThrow(new ValidationException("사유는 200자 이하로 입력해 주세요."));

        mockMvc.perform(post(CANCEL).contentType("application/json").content("{\"reason\":\"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "user-1")
    void 실패_깨진_본문은_400이다() throws Exception {
        mockMvc.perform(post(CANCEL).contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderCancelService);
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_주문상세는_새_상태와_배송_취소_시각을_함께_내려준다() throws Exception {
        Product product = new Product("티셔츠", "설명", 5000, "http://img/1", 5);
        Order order = new Order("user-1", "key-12345", "hash", "홍길동", "010-1234-5678", "서울시", 13_500, 3_500);
        OrderItem item = new OrderItem(product, 2, 5000);
        order.addItem(item);
        ReflectionTestUtils.setField(order, "id", "o1");
        ReflectionTestUtils.setField(item, "id", "item-1");
        ReflectionTestUtils.setField(order, "status", Order.STATUS_SHIPPED);
        ReflectionTestUtils.setField(order, "shippedAt", Instant.parse("2026-06-16T01:00:00Z"));
        ReflectionTestUtils.setField(order, "deliveredAt", Instant.parse("2026-06-17T01:00:00Z"));
        ReflectionTestUtils.setField(order, "cancelledAt", Instant.parse("2026-06-18T01:00:00Z"));
        when(orderService.getOrder("o1", "user-1")).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/orders/o1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.shippedAt").value("2026-06-16T01:00:00Z"))
                .andExpect(jsonPath("$.deliveredAt").value("2026-06-17T01:00:00Z"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-06-18T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].productName").value("티셔츠"));
    }

    @Test
    @WithMockUser(username = "user-1")
    void 성공_주문목록도_새_시각_필드를_내려주고_없으면_null이다() throws Exception {
        Order order = new Order("user-1", "key-12345", "hash", "홍길동", "010", "서울시", 1000, 0);
        ReflectionTestUtils.setField(order, "id", "o1");
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-06-15T00:00:00Z"));
        ReflectionTestUtils.setField(order, "status", Order.STATUS_CANCELLED);
        ReflectionTestUtils.setField(order, "cancelledAt", Instant.parse("2026-06-18T01:00:00Z"));
        when(orderService.listMyOrders("user-1", 10, null)).thenReturn(new OrderService.OrderPage(
                java.util.List.of(new OrderService.OrderSummary(order, 1, "티셔츠")), null));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.orders[0].cancelledAt").value("2026-06-18T01:00:00Z"))
                .andExpect(jsonPath("$.orders[0].shippedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.orders[0].deliveredAt").value(org.hamcrest.Matchers.nullValue()));
    }
}
