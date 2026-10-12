package com.shop.backend.infrastructure.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shop.backend.application.service.PaymentGateway;
import com.shop.backend.domain.error.PaymentNotConfiguredException;
import com.shop.backend.domain.error.PaymentProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
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
}
