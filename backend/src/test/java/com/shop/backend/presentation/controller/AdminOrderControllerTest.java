package com.shop.backend.presentation.controller;

import com.shop.backend.application.service.OrderCancelService;
import com.shop.backend.application.service.OrderService;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.CancelAlreadyDoneException;
import com.shop.backend.domain.error.CancelProviderStateException;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
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
import static org.mockito.Mockito.never;
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

    // cancel/check and cancel/abandon (a stuck CANCELING order, docs/cancel-resolve-design.md)

    private static final String[] RESOLVE_PATHS = {"/api/admin/orders/o1/cancel/check", "/api/admin/orders/o1/cancel/abandon"};

    @Test
    @WithMockUser(username = "admin-42", roles = "ADMIN")
    void 성공_취소_상태_확인은_상태와_결제사_상태를_돌려주고_캐시를_막으며_로그인한_사람을_넘긴다() throws Exception {
        when(orderCancelService.checkCancelByAdmin("o1", "admin-42"))
                .thenReturn(new OrderCancelService.CancelResolution("CANCELLED", "CANCELLED"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/check"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.providerStatus").value("CANCELLED"));

        verify(orderCancelService).checkCancelByAdmin("o1", "admin-42");
        verify(orderCancelService, never()).abandonCancelByAdmin(anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "seller-7", roles = "SELLER")
    void 성공_취소_포기는_원래_상태와_결제사_상태를_돌려주고_캐시를_막으며_로그인한_사람을_넘긴다() throws Exception {
        when(orderCancelService.abandonCancelByAdmin("o1", "seller-7"))
                .thenReturn(new OrderCancelService.CancelResolution("SHIPPED", "PAID"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/abandon"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.providerStatus").value("PAID"));

        verify(orderCancelService).abandonCancelByAdmin("o1", "seller-7");
        verify(orderCancelService, never()).checkCancelByAdmin(anyString(), anyString());
    }

    @Test
    @WithMockUser(username = "seller-7", roles = "SELLER")
    void 성공_판매자도_취소_상태_확인을_할_수_있고_결제사가_아직_안_취소했으면_취소중으로_남는다() throws Exception {
        when(orderCancelService.checkCancelByAdmin("o1", "seller-7"))
                .thenReturn(new OrderCancelService.CancelResolution("CANCELING", "PAID"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELING"))
                .andExpect(jsonPath("$.providerStatus").value("PAID"));
    }

    @Test
    @WithMockUser(username = "admin-42", roles = "ADMIN")
    void 성공_관리자도_취소_포기를_할_수_있다() throws Exception {
        when(orderCancelService.abandonCancelByAdmin("o1", "admin-42"))
                .thenReturn(new OrderCancelService.CancelResolution("PAID", "NOT_FOUND"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/abandon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.providerStatus").value("NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void 실패_일반_회원은_취소_확인과_포기_모두_403이고_서비스를_부르지_않는다() throws Exception {
        for (String path : RESOLVE_PATHS) {
            mockMvc.perform(post(path)).andExpect(status().isForbidden());
        }

        verifyNoInteractions(orderService, orderCancelService);
    }

    @Test
    void 실패_로그인_없이_취소_확인과_포기를_누르면_401이다() throws Exception {
        for (String path : RESOLVE_PATHS) {
            mockMvc.perform(post(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }

        verifyNoInteractions(orderService, orderCancelService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_없는_주문의_취소_확인과_포기는_404다() throws Exception {
        when(orderCancelService.checkCancelByAdmin(anyString(), anyString())).thenThrow(new OrderNotFoundException());
        when(orderCancelService.abandonCancelByAdmin(anyString(), anyString())).thenThrow(new OrderNotFoundException());

        for (String path : RESOLVE_PATHS) {
            mockMvc.perform(post(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_취소중이_아닌_주문은_409_INVALID_ORDER_STATE다() throws Exception {
        when(orderCancelService.checkCancelByAdmin(anyString(), anyString()))
                .thenThrow(new InvalidOrderStateException("취소 처리 중인 주문만 취소 상태를 확인할 수 있어요."));
        when(orderCancelService.abandonCancelByAdmin(anyString(), anyString()))
                .thenThrow(new InvalidOrderStateException("취소 처리 중인 주문만 취소를 포기할 수 있어요."));

        for (String path : RESOLVE_PATHS) {
            mockMvc.perform(post(path))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATE"))
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 실패_결제사가_이미_취소했으면_포기는_409_CANCEL_ALREADY_DONE이고_안내_문구를_돌려준다() throws Exception {
        when(orderCancelService.abandonCancelByAdmin(anyString(), anyString())).thenThrow(new CancelAlreadyDoneException());

        mockMvc.perform(post("/api/admin/orders/o1/cancel/abandon"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_ALREADY_DONE"))
                .andExpect(jsonPath("$.error").value(new CancelAlreadyDoneException().getMessage()));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 실패_결제사가_그_밖의_상태면_포기는_409_CANCEL_PROVIDER_STATE이고_문구에_상태가_들어_있다() throws Exception {
        when(orderCancelService.abandonCancelByAdmin(anyString(), anyString()))
                .thenThrow(new CancelProviderStateException("PARTIAL_CANCELLED"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/abandon"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PARTIAL_CANCELLED")));
    }

    @Test
    @WithMockUser(roles = "SELLER")
    void 실패_결제사에_결제_정보가_없으면_포기는_409_CANCEL_PROVIDER_STATE이고_결제_정보_없음_안내를_돌려준다() throws Exception {
        when(orderCancelService.abandonCancelByAdmin(anyString(), anyString())).thenThrow(new CancelProviderStateException());

        mockMvc.perform(post("/api/admin/orders/o1/cancel/abandon"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCEL_PROVIDER_STATE"))
                .andExpect(jsonPath("$.error").value("결제사에 이 주문의 결제 정보가 없어요. PortOne 콘솔에서 확인해 주세요."));
    }

    @Test
    @WithMockUser(username = "seller-7", roles = "SELLER")
    void 성공_이미_끝난_주문의_확인은_결제사_상태가_null로_나가고_키는_남아_있다() throws Exception {
        when(orderCancelService.checkCancelByAdmin("o1", "seller-7"))
                .thenReturn(new OrderCancelService.CancelResolution("REFUNDED", null));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/check"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.providerStatus").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "seller-7", roles = "SELLER")
    void 성공_결제_정보가_없거나_상태를_모르면_NOT_FOUND_UNKNOWN을_그대로_돌려준다() throws Exception {
        when(orderCancelService.checkCancelByAdmin("o1", "seller-7"))
                .thenReturn(new OrderCancelService.CancelResolution("CANCELING", "NOT_FOUND"));
        when(orderCancelService.checkCancelByAdmin("o2", "seller-7"))
                .thenReturn(new OrderCancelService.CancelResolution("CANCELING", "UNKNOWN"));

        mockMvc.perform(post("/api/admin/orders/o1/cancel/check"))
                .andExpect(jsonPath("$.providerStatus").value("NOT_FOUND"));
        mockMvc.perform(post("/api/admin/orders/o2/cancel/check"))
                .andExpect(jsonPath("$.providerStatus").value("UNKNOWN"));
    }

    @Test
    @WithMockUser(username = "u", roles = "ADMIN")
    void 실패_결제사_조회_실패나_결제_설정_없음은_취소_확인과_포기_모두_503이다() throws Exception {
        when(orderCancelService.checkCancelByAdmin("o1", "u")).thenThrow(new PaymentProviderUnavailableException());
        when(orderCancelService.abandonCancelByAdmin("o1", "u")).thenThrow(new PaymentProviderUnavailableException());
        when(orderCancelService.checkCancelByAdmin("o2", "u")).thenThrow(new PaymentNotConfiguredException());
        when(orderCancelService.abandonCancelByAdmin("o2", "u")).thenThrow(new PaymentNotConfiguredException());

        for (String action : new String[]{"check", "abandon"}) {
            mockMvc.perform(post("/api/admin/orders/o1/cancel/" + action))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));
            mockMvc.perform(post("/api/admin/orders/o2/cancel/" + action))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PAYMENT_NOT_CONFIGURED"));
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 실패_GET으로는_취소_확인도_포기도_할_수_없다() throws Exception {
        for (String path : RESOLVE_PATHS) {
            mockMvc.perform(get(path)).andExpect(status().is4xxClientError());
        }

        verifyNoInteractions(orderCancelService);
    }
}
