package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotCancellableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The three-step cancel/refund flow against mocks: the order of calls and what happens when step 2 fails. */
@ExtendWith(MockitoExtension.class)
class OrderCancelServiceTest {

    private static final String ORDER = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "user-1";

    @Mock
    private PaymentGateway gateway;
    @Mock
    private PaymentTransactions transactions;

    private OrderCancelService service() {
        return new OrderCancelService(gateway, transactions);
    }

    private void configured() {
        when(gateway.isConfigured()).thenReturn(true);
    }

    @Test
    void 성공_고객_취소는_잠금_포트원_마무리_순서로_진행하고_새_상태를_돌려준다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, "단순 변심"))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED, Order.CancelOutcome.RESTORE_STOCK));

        assertThat(service().cancelByCustomer(ORDER, USER, " 단순 변심 ")).isEqualTo(Order.STATUS_CANCELLED);

        InOrder inOrder = inOrder(transactions, gateway);
        inOrder.verify(transactions).beginCancel(ORDER, USER, "단순 변심");
        inOrder.verify(gateway).cancel(ORDER, "단순 변심", PaymentGateway.Requester.CUSTOMER);
        inOrder.verify(transactions).applyCancelled(ORDER);
    }

    @Test
    void 성공_사유를_안_보내면_포트원에는_기본_사유를_보내고_주문에는_사유를_남기지_않는다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_CANCELLED, Order.CancelOutcome.RESTORE_STOCK));

        service().cancelByCustomer(ORDER, USER, null);

        verify(gateway).cancel(ORDER, "고객 요청 취소", PaymentGateway.Requester.CUSTOMER);
    }

    @Test
    void 성공_이미_취소된_주문은_포트원을_부르지_않고_200_멱등으로_끝난다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELLED, false));

        assertThat(service().cancelByCustomer(ORDER, USER, null)).isEqualTo(Order.STATUS_CANCELLED);

        verify(gateway, never()).cancel(anyString(), anyString(), any());
        verify(transactions, never()).applyCancelled(anyString());
    }

    @Test
    void 실패_남의_주문이거나_없는_주문이면_포트원을_부르지_않고_404다() {
        configured();
        when(transactions.beginCancel(ORDER, "other", null)).thenThrow(new OrderNotFoundException());

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, "other", null))
                .isInstanceOf(OrderNotFoundException.class);

        verify(gateway, never()).cancel(anyString(), anyString(), any());
    }

    @Test
    void 실패_로그인_정보가_없으면_판매자_경로로_새지_않고_404다() {
        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, null, null))
                .isInstanceOf(OrderNotFoundException.class);

        verifyNoInteractions(gateway, transactions);
    }

    @Test
    void 실패_배송이_시작된_주문은_409이고_포트원을_부르지_않는다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenThrow(new OrderNotCancellableException("배송이 시작되어 바로 취소할 수 없어요."));

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, null))
                .isInstanceOf(OrderNotCancellableException.class);

        verify(gateway, never()).cancel(anyString(), anyString(), any());
    }

    @Test
    void 실패_포트원이_오류로_거절하면_직전_상태로_되돌리고_취소_장애를_던진다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        doThrow(new CancelProviderUnavailableException(false)).when(gateway).cancel(anyString(), anyString(), any());

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, null))
                .isInstanceOf(CancelProviderUnavailableException.class);

        verify(transactions).revertCancel(ORDER);
        verify(transactions, never()).applyCancelled(anyString());
    }

    @Test
    void 실패_결과를_알_수_없는_타임아웃은_취소중으로_두고_되돌리지_않는다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        doThrow(new CancelProviderUnavailableException(true)).when(gateway).cancel(anyString(), anyString(), any());

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, null))
                .isInstanceOf(CancelProviderUnavailableException.class);

        verify(transactions, never()).revertCancel(anyString());
        verify(transactions, never()).applyCancelled(anyString());
    }

    @Test
    void 실패_되돌리기_자체가_실패해도_고객에게는_취소_장애를_그대로_알린다() {
        configured();
        when(transactions.beginCancel(ORDER, USER, null))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        doThrow(new CancelProviderUnavailableException(false)).when(gateway).cancel(anyString(), anyString(), any());
        doThrow(new IllegalStateException("db down")).when(transactions).revertCancel(ORDER);

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, null))
                .isInstanceOf(CancelProviderUnavailableException.class);
    }

    @Test
    void 실패_결제_설정이_없으면_아무것도_바꾸지_않고_설정_없음이다() {
        when(gateway.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, null))
                .isInstanceOf(PaymentNotConfiguredException.class);

        verifyNoInteractions(transactions);
    }

    @Test
    void 실패_사유가_200자를_넘으면_아무것도_하지_않고_400이다() {
        assertThatThrownBy(() -> service().cancelByCustomer(ORDER, USER, "a".repeat(201)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(gateway, transactions);
    }

    // seller refund

    @Test
    void 성공_판매자_환불은_판매자_요청자로_포트원을_부르고_환불됨을_돌려준다() {
        configured();
        when(transactions.beginCancel(ORDER, null, "반품 입고"))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        when(transactions.applyCancelled(ORDER)).thenReturn(
                new PaymentTransactions.CancelApplied(Order.STATUS_REFUNDED, Order.CancelOutcome.NO_STOCK_CHANGE));

        assertThat(service().refundByAdmin(ORDER, "반품 입고")).isEqualTo(Order.STATUS_REFUNDED);

        verify(gateway).cancel(ORDER, "반품 입고", PaymentGateway.Requester.ADMIN);
    }

    @Test
    void 실패_판매자_환불에는_사유가_필수다() {
        for (String reason : new String[]{null, "", "  "}) {
            assertThatThrownBy(() -> service().refundByAdmin(ORDER, reason)).isInstanceOf(ValidationException.class);
        }

        verifyNoInteractions(gateway, transactions);
    }

    @Test
    void 실패_환불할_수_없는_상태면_409이고_포트원을_부르지_않는다() {
        configured();
        when(transactions.beginCancel(ORDER, null, "x")).thenThrow(new InvalidOrderStateException("배송이 시작된 주문만"));

        assertThatThrownBy(() -> service().refundByAdmin(ORDER, "x")).isInstanceOf(InvalidOrderStateException.class);

        verify(gateway, never()).cancel(anyString(), anyString(), any());
    }

    @Test
    void 실패_판매자_환불도_포트원_오류면_직전_상태로_되돌린다() {
        configured();
        when(transactions.beginCancel(ORDER, null, "x"))
                .thenReturn(new PaymentTransactions.CancelStart(Order.STATUS_CANCELING, true));
        doThrow(new CancelProviderUnavailableException(false)).when(gateway).cancel(anyString(), anyString(), any());

        assertThatThrownBy(() -> service().refundByAdmin(ORDER, "x"))
                .isInstanceOf(CancelProviderUnavailableException.class);

        verify(transactions).revertCancel(ORDER);
    }
}
