package com.shop.backend.application.service;

import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.error.CancelAlreadyDoneException;
import com.shop.backend.domain.error.CancelProviderStateException;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.InvalidOrderStateException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Customer cancel and seller refund. NOT @Transactional, same three steps as {@link PaymentService#complete}: 1) a short
 * transaction locks the order and moves it to CANCELING, 2) the provider call runs OUTSIDE any transaction (it must not
 * hold one of the few pooled connections), 3) a short transaction finishes it (CANCELLED/REFUNDED, stock back when unshipped).
 */
@Service
public class OrderCancelService {

    private static final Logger logger = LoggerFactory.getLogger(OrderCancelService.class);
    private static final String DEFAULT_CUSTOMER_REASON = "고객 요청 취소";

    private static final String PROVIDER_CANCELLED = "CANCELLED";
    private static final String PROVIDER_NOT_FOUND = "NOT_FOUND";
    private static final String PROVIDER_UNKNOWN = "UNKNOWN";

    private final PaymentGateway gateway;
    private final PaymentTransactions transactions;
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;

    public OrderCancelService(PaymentGateway gateway, PaymentTransactions transactions, OrderRepository orderRepository,
                              PaymentService paymentService) {
        this.gateway = gateway;
        this.transactions = transactions;
        this.orderRepository = orderRepository;
        this.paymentService = paymentService;
    }

    /** The order status after a seller's check/abandon, and what the provider said (PAID, CANCELLED, NOT_FOUND, ...). */
    public record CancelResolution(String status, String providerStatus) {
    }

    /**
     * The seller asks "did the provider cancel it?" for a stuck CANCELING order. Only a verified full CANCELLED at the
     * provider finishes the order (same path as the cancel webhook); anything else changes nothing. An order that is
     * already CANCELLED/REFUNDED is answered as is (idempotent).
     */
    public CancelResolution checkCancelByAdmin(String orderId, String actor) {
        String status = loadStatus(orderId);
        if (isFinal(status)) {
            return logged(orderId, "check", null, status, actor); // provider not asked
        }
        requireCanceling(status, "취소 처리 중인 주문만 취소 상태를 확인할 수 있어요.");
        // provider lookup outside any transaction; a failure propagates as 503 and nothing changes
        PaymentGateway.Payment payment = gateway.find(orderId).orElse(null);
        String providerStatus = providerStatus(payment);
        if (paymentService.isCancelledAtProvider(orderId, payment)) {
            status = transactions.applyCancelled(orderId).status();
        }
        return logged(orderId, "check", providerStatus, status, actor);
    }

    /**
     * The seller gives up a stuck cancel. Allowed only when the provider reports THIS order's payment (id and store)
     * as PAID; CANCELLED, no payment at all (a 404 may be a wrong API base or another store's secret, so reverting
     * could give goods away) and every other status are refused. Returns the restored status.
     */
    public CancelResolution abandonCancelByAdmin(String orderId, String actor) {
        requireCanceling(loadStatus(orderId), "취소 처리 중인 주문만 취소를 포기할 수 있어요.");
        PaymentGateway.Payment payment = gateway.find(orderId).orElse(null);
        String providerStatus = providerStatus(payment);
        if (payment != null && PROVIDER_CANCELLED.equals(payment.status())) {
            throw new CancelAlreadyDoneException();
        }
        if (payment == null) {
            throw new CancelProviderStateException();
        }
        if (!paymentService.isPaidAtProvider(orderId, payment)) {
            throw new CancelProviderStateException(providerStatus);
        }
        // re-checks CANCELING under the row lock; a webhook may have finished the order since step 1
        String restored = transactions.revertCancel(orderId);
        if (restored == null) {
            throw new OrderNotFoundException();
        }
        if (isFinal(restored)) {
            throw new CancelAlreadyDoneException();
        }
        return logged(orderId, "abandon", providerStatus, restored, actor);
    }

    private String loadStatus(String orderId) {
        if (!paymentService.isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        return orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new).getStatus();
    }

    private static boolean isFinal(String status) {
        return Order.STATUS_CANCELLED.equals(status) || Order.STATUS_REFUNDED.equals(status);
    }

    private static void requireCanceling(String status, String message) {
        if (!Order.STATUS_CANCELING.equals(status)) {
            throw new InvalidOrderStateException(message);
        }
    }

    // The provider's text reaches the log, a 409 message and the response: only [A-Za-z0-9_], at most 40 characters.
    private static String providerStatus(PaymentGateway.Payment payment) {
        if (payment == null) {
            return PROVIDER_NOT_FOUND;
        }
        String status = payment.status();
        if (status == null || status.isBlank()) {
            return PROVIDER_UNKNOWN;
        }
        String clean = status.replaceAll("[^A-Za-z0-9_]", "?");
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private CancelResolution logged(String orderId, String action, String providerStatus, String status, String actor) {
        logger.info("Cancel resolve: orderId={} action={} providerStatus={} status={} actor={}", orderId, action,
                providerStatus, status, actor);
        return new CancelResolution(status, providerStatus);
    }

    /** The owner cancels a PAID order in full; returns the new status. Idempotent for CANCELLED and for a CANCELING one it resumes. */
    public String cancelByCustomer(String orderId, String userId, String reason) {
        if (userId == null) {
            throw new OrderNotFoundException(); // null would select the seller path below
        }
        String cleanReason = OrderValidation.normalizeCancelReason(reason, false);
        return cancel(orderId, userId, cleanReason, cleanReason == null ? DEFAULT_CUSTOMER_REASON : cleanReason,
                PaymentGateway.Requester.CUSTOMER);
    }

    /** The seller refunds a shipped/delivered order in full (the reason is required); returns the new status. */
    public String refundByAdmin(String orderId, String reason) {
        String cleanReason = OrderValidation.normalizeCancelReason(reason, true);
        return cancel(orderId, null, cleanReason, cleanReason, PaymentGateway.Requester.ADMIN);
    }

    private String cancel(String orderId, String userId, String storedReason, String providerReason,
                          PaymentGateway.Requester requester) {
        if (!gateway.isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        // 1) lock, validate, PAID/SHIPPED/DELIVERED -> CANCELING
        PaymentTransactions.CancelStart start = transactions.beginCancel(orderId, userId, storedReason);
        if (!start.callProvider()) {
            return start.status(); // already CANCELLED / REFUNDED
        }
        // 2) provider call, outside any transaction (the payment id is the order id)
        try {
            gateway.cancel(orderId, providerReason, requester);
        } catch (CancelProviderUnavailableException e) {
            if (!e.isOutcomeUnknown()) {
                revertQuietly(orderId);
            } // else: it may have been cancelled; stay CANCELING, the webhook or the next request finishes it
            throw e;
        }
        // 3) lock, CANCELING -> CANCELLED/REFUNDED, stock back
        return transactions.applyCancelled(orderId).status();
    }

    private void revertQuietly(String orderId) {
        try {
            transactions.revertCancel(orderId);
        } catch (RuntimeException e) {
            // the customer still gets the 503; the order stays CANCELING and a retry or the webhook resolves it
            logger.error("Could not revert CANCELING after a provider failure: orderId={} {}", orderId,
                    e.getClass().getSimpleName());
        }
    }
}
