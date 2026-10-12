package com.shop.backend.application.service;

import java.util.Optional;

/** Server-to-server lookup of a payment at the payment provider (the only source of truth for "was it paid"). */
public interface PaymentGateway {

    /**
     * The fields we verify; amounts are KRW won. {@code total} is null when the provider did not send one;
     * {@code channelType} ("LIVE"/"TEST") is null when missing.
     */
    record Payment(String id, String storeId, String status, Long total, String currency, String transactionId,
                   String channelType) {
    }

    /** False when the API secret is not set: the payment feature is off. */
    boolean isConfigured();

    /** Empty when the provider has no such payment (nothing paid yet). Throws PaymentProviderUnavailableException on failure/timeout. */
    Optional<Payment> find(String paymentId);
}
