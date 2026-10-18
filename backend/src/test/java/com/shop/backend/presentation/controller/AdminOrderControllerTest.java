package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
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
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOrderController.class)
@Import({SecurityConfig.class, JwtService.class})
class AdminOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private OrderService orderService;
    @MockBean
    private OrderCancelService orderCancelService;

    private static OrderService.OrderPage onePage(String nextCursor) {
        Order order = new Order("user-1", "k", "h", "홍길동", "010-1234-5678", "서울시 어딘가 1", 13_500, 3_500);
        ReflectionTestUtils.setField(order, "id", "o1");
        ReflectionTestUtils.setField(order, "status", Order.STATUS_SHIPPED);
        ReflectionTestUtils.setField(order, "createdAt", Instant.parse("2026-06-15T00:00:00Z"));
        ReflectionTestUtils.setField(order, "paidAt", Instant.parse("2026-06-15T00:05:00Z"));
        ReflectionTestUtils.setField(order, "shippedAt", Instant.parse("2026-06-16T00:00:00Z"));
        return new OrderService.OrderPage(List.of(new OrderService.OrderSummary(order, 2, "티셔츠")), nextCursor);
    }

    // list

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_관리자가_주문목록을_조회하면_계약대로_내려주고_캐시를_막는다() throws Exception {
        when(orderService.listAdminOrders("SHIPPED", 5, "cur")).thenReturn(onePage("next"));

        mockMvc.perform(get("/api/admin/orders").param("status", "SHIPPED").param("size", "5").param("cursor", "cur"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                .andExpect(jsonPath("$.orders[0].id").value("o1"))
                .andExpect(jsonPath("$.orders[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$.orders[0].totalAmount").value(13500))
                .andExpect(jsonPath("$.orders[0].shippingFee").value(3500))
                .andExpect(jsonPath("$.orders[0].createdAt").value("2026-06-15T00:00:00Z"))
                .andExpect(jsonPath("$.orders[0].paidAt").value("2026-06-15T00:05:00Z"))
                .andExpect(jsonPath("$.orders[0].shippedAt").value("2026-06-16T00:00:00Z"))
                .andExpect(jsonPath("$.orders[0].deliveredAt").value(nullValue()))
                .andExpect(jsonPath("$.orders[0].customerName").value("홍길동"))
                .andExpect(jsonPath("$.orders[0].customerPhone").value("010-1234-5678"))
                .andExpect(jsonPath("$.orders[0].customerAddress").value("서울시 어딘가 1"))
                .andExpect(jsonPath("$.orders[0].itemCount").value(2))
                .andExpect(jsonPath("$.orders[0].firstItemName").value("티셔츠"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_판매자도_조회할_수_있고_기본_크기는_20이며_마지막_페이지는_nextCursor가_null이다() throws Exception {
        when(orderService.listAdminOrders(isNull(), org.mockito.ArgumentMatchers.eq(20), isNull())).thenReturn(onePage(null));

        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_알려지지_않은_상태나_크기_초과는_400이다() throws Exception {
        when(orderService.listAdminOrders("NOPE", 20, null)).thenThrow(new ValidationException("주문 상태가 올바르지 않아요."));
        when(orderService.listAdminOrders(null, 51, null)).thenThrow(new ValidationException("주문 수는 1~50개 사이여야 해요."));

        mockMvc.perform(get("/api/admin/orders").param("status", "NOPE")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/orders").param("size", "51")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/orders").param("size", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void 실패_로그인_없이_조회하면_401이다() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(orderService, orderCancelService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void 실패_일반_회원은_목록과_처리_모두_403이다() throws Exception {
        mockMvc.perform(get("/api/admin/orders")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/orders/o1/ship")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/orders/o1/deliver")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/orders/o1/refund").contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService, orderCancelService);
    }

    // ship / deliver

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_배송_시작과_배송_완료는_새_상태를_돌려준다() throws Exception {
        when(orderService.shipOrder("o1")).thenReturn(Order.STATUS_SHIPPED);
        when(orderService.deliverOrder("o1")).thenReturn(Order.STATUS_DELIVERED);

        mockMvc.perform(post("/api/admin/orders/o1/ship"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        mockMvc.perform(post("/api/admin/orders/o1/deliver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_잘못된_상태의_배송_처리는_409_INVALID_ORDER_STATE다() throws Exception {
        when(orderService.shipOrder("o1")).thenThrow(new InvalidOrderStateException("결제가 완료된 주문만 배송을 시작할 수 있어요."));
        when(orderService.deliverOrder("o1")).thenThrow(new InvalidOrderStateException("배송 중인 주문만 배송 완료로 바꿀 수 있어요."));

        mockMvc.perform(post("/api/admin/orders/o1/ship"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        mockMvc.perform(post("/api/admin/orders/o1/deliver"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_없는_주문의_배송_처리는_404다() throws Exception {
        when(orderService.shipOrder("nope")).thenThrow(new OrderNotFoundException());

        mockMvc.perform(post("/api/admin/orders/nope/ship")).andExpect(status().isNotFound());
    }

    // refund

    @Test
    @WithMockUser(roles = "ADMIN")
    void 성공_환불은_사유와_함께_서비스에_넘기고_새_상태를_돌려준다() throws Exception {
        when(orderCancelService.refundByAdmin("o1", "하자 상품")).thenReturn(Order.STATUS_REFUNDED);

        mockMvc.perform(post("/api/admin/orders/o1/refund").contentType("application/json").content("{\"reason\":\"하자 상품\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 성공_판매자가_배송_전_결제완료_주문을_취소하면_200과_취소됨을_돌려준다() throws Exception {
        when(orderCancelService.refundByAdmin("o1", "품절")).thenReturn(Order.STATUS_CANCELLED);

        mockMvc.perform(post("/api/admin/orders/o1/refund").contentType("application/json").content("{\"reason\":\"품절\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_환불에_사유가_없거나_본문이_없으면_400이다() throws Exception {
        when(orderCancelService.refundByAdmin(anyString(), any())).thenThrow(new ValidationException("사유를 입력해 주세요."));

        mockMvc.perform(post("/api/admin/orders/o1/refund").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/orders/o1/refund"))
                .andExpect(status().isBadRequest());
        verify(orderCancelService, org.mockito.Mockito.times(2)).refundByAdmin("o1", null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_환불할_수_없는_상태는_409이고_포트원_장애는_503이다() throws Exception {
        when(orderCancelService.refundByAdmin("o1", "x")).thenThrow(new InvalidOrderStateException("결제가 완료된 주문만 취소·환불할 수 있어요."));
        when(orderCancelService.refundByAdmin("o2", "x")).thenThrow(new CancelProviderUnavailableException(false));

        mockMvc.perform(post("/api/admin/orders/o1/refund").contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"));
        mockMvc.perform(post("/api/admin/orders/o2/refund").contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_UNAVAILABLE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_없는_주문의_환불은_404다() throws Exception {
        when(orderCancelService.refundByAdmin("nope", "x")).thenThrow(new OrderNotFoundException());

        mockMvc.perform(post("/api/admin/orders/nope/refund").contentType("application/json").content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(orderService);
    }
}
