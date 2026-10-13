package com.shop.backend.application.service;

import com.shop.backend.domain.entity.OrderValidation;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
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

    private final PaymentGateway gateway;
    private final PaymentTransactions transactions;

    public OrderCancelService(PaymentGateway gateway, PaymentTransactions transactions) {
        this.gateway = gateway;
        this.transactions = transactions;
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
