package com.shop.backend.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.domain.entity.Order;
import com.shop.backend.domain.error.OrderNotFoundException;
import com.shop.backend.domain.error.PaymentAmountMismatchException;
import com.shop.backend.domain.error.PaymentChannelNotAllowedException;
import com.shop.backend.domain.error.PaymentNeedsReviewException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentNotConfirmedException;
import com.shop.backend.domain.error.ValidationException;
import com.shop.backend.infrastructure.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Payment confirmation. NOT @Transactional: 1) read the order, 2) ask the provider (network, up to ~7 s, must not
 * hold one of the 5 pooled DB connections), 3) a short transaction in {@link PaymentTransactions} applies the result.
 */
@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    static final int EXPIRE_BATCH = 100;
    private static final String CURRENCY = "KRW";
    private static final String PAID = "PAID";
    private static final String CHANNEL_LIVE = "LIVE";
    private static final String CHANNEL_TEST = "TEST";
    private static final String CANCELLED = "CANCELLED";
    private static final String WEBHOOK_PAID_TYPE = "Transaction.Paid";
    private static final String WEBHOOK_CANCELLED_TYPE = "Transaction.Cancelled";
    private static final String WEBHOOK_PARTIAL_CANCELLED_TYPE = "Transaction.PartialCancelled";

    private final OrderRepository orderRepository;
    private final PaymentGateway gateway;
    private final PaymentTransactions transactions;
    private final WebhookSignatureVerifier webhookVerifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String storeId;
    private final boolean allowTestChannel;

    public PaymentService(OrderRepository orderRepository, PaymentGateway gateway, PaymentTransactions transactions,
                          WebhookSignatureVerifier webhookVerifier, ObjectMapper objectMapper, Clock clock,
                          @Value("${portone.store-id:}") String storeId,
                          @Value("${portone.allow-test-channel:false}") boolean allowTestChannel) {
        this.allowTestChannel = allowTestChannel;
        this.orderRepository = orderRepository;
        this.gateway = gateway;
        this.transactions = transactions;
        this.webhookVerifier = webhookVerifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.storeId = storeId;
    }

    /**
     * Confirms the order's payment with the provider and marks it PAID; returns the order status. {@code userId} null
     * (webhook) skips the owner check; otherwise someone else's or a missing order is a 404.
     */
    public String complete(String orderId, String userId) {
        if (storeId == null || storeId.isBlank() || !gateway.isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        // 1) read (its own short repository transaction)
        Optional<Order> found;
        if (userId == null) {
            found = orderRepository.findById(orderId);
        } else if (userId.isBlank()) {
            found = Optional.empty(); // a blank id must never reach the query
        } else {
            found = orderRepository.findByIdAndUserId(orderId, userId);
        }
        Order order = found.orElseThrow(OrderNotFoundException::new);
        if (Order.STATUS_PAID.equals(order.getStatus())) {
            return Order.STATUS_PAID; // already confirmed: no provider call needed
        }
        if (Order.STATUS_PAYMENT_REVIEW.equals(order.getStatus())) {
            throw new PaymentNeedsReviewException();
        }

        // 2) provider call, outside any transaction
        PaymentGateway.Payment payment = gateway.find(orderId).orElseThrow(PaymentNotConfirmedException::new);
        if (!PAID.equals(payment.status())) {
            throw new PaymentNotConfirmedException();
        }
        if (!orderId.equals(payment.id()) || !storeId.equals(payment.storeId()) || !CURRENCY.equals(payment.currency())
                || payment.total() == null || payment.total().longValue() != order.getTotalAmount().longValue()) {
            // no secrets here: ids and amounts only
            logger.error("Payment does not match the order: orderId={} paymentId={} serverAmount={} providerAmount={} "
                            + "providerCurrency={}", orderId, payment.id(), order.getTotalAmount(), payment.total(),
                    payment.currency());
            throw new PaymentAmountMismatchException();
        }
        // A TEST-channel payment moves no money: only LIVE counts unless test channels are explicitly allowed.
        // Missing/unknown channel type is treated as not allowed.
        boolean live = CHANNEL_LIVE.equals(payment.channelType());
        if (!live && !(allowTestChannel && CHANNEL_TEST.equals(payment.channelType()))) {
            logger.error("Payment channel not allowed: orderId={} paymentId={} channelType={}", orderId, payment.id(),
                    payment.channelType());
            throw new PaymentChannelNotAllowedException();
        }

        // 3) short transaction: lock the row, move the state
        String result = transactions.applyPaid(orderId, payment.transactionId());
        if (Order.STATUS_PAYMENT_REVIEW.equals(result)) {
            throw new PaymentNeedsReviewException();
        }
        return result;
    }

    /**
     * Provider webhook (backup for the browser's confirm call). Signature/timestamp errors -> ValidationException (400),
     * other event types and unknown payment ids are ignored (200), a provider outage propagates as 503 so the provider retries.
     */
    public void handleWebhook(String webhookId, String timestamp, String signature, String rawBody) {
        webhookVerifier.verify(webhookId, timestamp, signature, rawBody);
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException e) {
            throw new ValidationException("요청 본문을 읽을 수 없습니다.");
        }
        // type first; data/paymentId are only looked at for the types we handle. Any odd shape is ignored (200).
        if (root == null || !root.path("type").isTextual()) {
            return;
        }
        String type = root.path("type").asText();
        if (!WEBHOOK_PAID_TYPE.equals(type) && !WEBHOOK_CANCELLED_TYPE.equals(type)
                && !WEBHOOK_PARTIAL_CANCELLED_TYPE.equals(type)) {
            return;
        }
        JsonNode paymentIdNode = root.path("data").path("paymentId");
        if (!root.path("data").isObject() || !paymentIdNode.isTextual() || paymentIdNode.asText().isBlank()) {
            return;
        }
        String paymentId = paymentIdNode.asText();
        if (!orderRepository.existsById(paymentId)) {
            logger.info("Webhook for a payment that is not one of our orders: paymentId={}", paymentId);
            return;
        }
        if (WEBHOOK_PARTIAL_CANCELLED_TYPE.equals(type)) {
            // not in our policy (full refunds only): the order stays as it is, an operator has to look at the console
            logger.error("Partial cancellation reported by the provider, order left unchanged; check it in the "
                    + "provider console: paymentId={}", paymentId);
            return;
        }
        try {
            if (WEBHOOK_CANCELLED_TYPE.equals(type)) {
                applyProviderCancel(paymentId);
            } else {
                complete(paymentId, null);
            }
        } catch (PaymentAmountMismatchException | PaymentNeedsReviewException | PaymentChannelNotAllowedException e) {
            // already logged at ERROR for the operator; a retry would give the same answer
            logger.info("Webhook processed without confirmation: paymentId={} code={}", paymentId, e.getCode());
        } catch (OrderNotFoundException e) {
            logger.info("Webhook for an order that no longer exists: paymentId={}", paymentId);
        }
    }

    /**
     * Transaction.Cancelled: the body is not trusted, the payment is looked up again and only a full CANCELLED payment
     * for this store and order id moves the order (see {@link Order#finishCancel}).
     */
    private void applyProviderCancel(String paymentId) {
        if (storeId == null || storeId.isBlank() || !gateway.isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        PaymentGateway.Payment payment = gateway.find(paymentId).orElse(null);
        if (payment == null || !CANCELLED.equals(payment.status()) || !paymentId.equals(payment.id())
                || !storeId.equals(payment.storeId())) {
            logger.warn("Cancel webhook ignored, the provider does not report this order's payment as cancelled: "
                    + "paymentId={} providerStatus={}", paymentId, payment == null ? null : payment.status());
            return;
        }
        PaymentTransactions.CancelApplied applied = transactions.applyCancelled(paymentId);
        if (applied.outcome() == Order.CancelOutcome.IGNORED) {
            logger.info("Payment cancelled at the provider for an order that was never paid: paymentId={} status={}",
                    paymentId, applied.status());
        } else if (applied.outcome() == Order.CancelOutcome.NO_STOCK_CHANGE
                && Order.STATUS_REFUNDED.equals(applied.status())) {
            logger.info("Order refunded after the payment was cancelled at the provider; stock not restored: "
                    + "paymentId={}", paymentId);
        }
    }

    /** Expires up to {@link #EXPIRE_BATCH} overdue PENDING_PAYMENT orders, each in its own transaction. Returns how many. */
    public int expirePending() {
        List<String> ids = orderRepository.findExpiredPendingIds(clock.instant(), PageRequest.of(0, EXPIRE_BATCH));
        int expired = 0;
        for (String id : ids) {
            try {
                if (transactions.expireOne(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // one stuck order (lock timeout, ...) must not stop the rest; it is picked up on the next run
                logger.warn("Could not expire order {}: {}", id, e.getClass().getSimpleName());
            }
        }
        return expired;
    }
}
