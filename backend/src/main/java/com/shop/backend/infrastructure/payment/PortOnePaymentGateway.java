package com.shop.backend.infrastructure.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.domain.error.CancelProviderUnavailableException;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

/** PortOne V2 REST: GET {api-base}/payments/{paymentId}. The API secret is never logged or put in a message. */
@Component
public class PortOnePaymentGateway implements PaymentGateway {

    private static final Logger logger = LoggerFactory.getLogger(PortOnePaymentGateway.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String id, String storeId, String status, Amount amount, String currency, String transactionId,
                    Channel channel) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Amount(Long total) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Channel(String type) {
        }
    }

    private static final String DEFAULT_API_BASE = "https://api.portone.io";
    // error body {"type":"PAYMENT_ALREADY_CANCELLED","message":...} (SDK errors/PaymentAlreadyCancelledError.kt)
    private static final String ALREADY_CANCELLED = "PAYMENT_ALREADY_CANCELLED";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiSecret;
    private final RestClient client;

    @Autowired
    public PortOnePaymentGateway(@Value("${portone.api-secret:}") String apiSecret,
                                 @Value("${portone.api-base:" + DEFAULT_API_BASE + "}") String apiBase) {
        this(apiSecret, apiBase, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    PortOnePaymentGateway(String apiSecret, String apiBase, Duration connectTimeout, Duration readTimeout) {
        this.apiSecret = apiSecret;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        String base = apiBase == null ? "" : apiBase.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.client = RestClient.builder().baseUrl(base.isEmpty() ? DEFAULT_API_BASE : base).requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return apiSecret != null && !apiSecret.isBlank();
    }

    @Override
    public Optional<Payment> find(String paymentId) {
        if (!isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        try {
            Response r = client.get().uri("/payments/{paymentId}", paymentId)
                    .header(HttpHeaders.AUTHORIZATION, "PortOne " + apiSecret)
                    .retrieve().body(Response.class);
            if (r == null) {
                throw new PaymentProviderUnavailableException();
            }
            return Optional.of(new Payment(r.id(), r.storeId(), r.status(), r.amount() == null ? null : r.amount().total(),
                    r.currency(), r.transactionId(), r.channel() == null ? null : r.channel().type()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // status only: the message/body may echo request details
            logger.error("PortOne lookup failed: status={}", e.getStatusCode().value());
            throw new PaymentProviderUnavailableException();
        } catch (RestClientException e) {
            logger.error("PortOne lookup failed: {}", e.getClass().getSimpleName());
            throw new PaymentProviderUnavailableException();
        }
    }

    /** POST /payments/{paymentId}/cancel with {reason, requester}: no amount = full cancel, no storeId = the secret's own store. */
    @Override
    public void cancel(String paymentId, String reason, Requester requester) {
        if (!isConfigured()) {
            throw new PaymentNotConfiguredException();
        }
        try {
            client.post().uri("/payments/{paymentId}/cancel", paymentId)
                    .header(HttpHeaders.AUTHORIZATION, "PortOne " + apiSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CancelRequest(reason, requester.name()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (isAlreadyCancelled(e)) {
                return; // someone (console, webhook race, an earlier try of ours) already cancelled it: that is the goal
            }
            // status only: the message/body may echo request details
            logger.error("PortOne cancel failed: status={}", e.getStatusCode().value());
            // 5xx (incl. proxy 502/504): the cancel may have gone through, so the outcome is unknown; 4xx is a definite refusal
            throw new CancelProviderUnavailableException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            // no answer (timeout, connection reset): the cancel may have gone through
            logger.error("PortOne cancel got no answer: {}", e.getClass().getSimpleName());
            throw new CancelProviderUnavailableException(true);
        }
    }

    record CancelRequest(String reason, String requester) {
    }

    private static boolean isAlreadyCancelled(RestClientResponseException e) {
        try {
            return ALREADY_CANCELLED.equals(MAPPER.readTree(e.getResponseBodyAsString()).path("type").asText());
        } catch (JsonProcessingException | RuntimeException ex) {
            return false;
        }
    }
}
