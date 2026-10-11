package com.smartlib.payment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.security.TokenHasher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Minimal Razorpay REST client (Orders + Payments). The key secret never leaves the server. */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);

    private final AppProperties.Razorpay config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public RazorpayClient(AppProperties properties, ObjectMapper mapper) {
        this.config = properties.razorpay();
        this.mapper = mapper;
    }

    public record Order(String id, long amount, String currency, String status) {
    }

    public record PaymentInfo(String id, String orderId, long amount, String currency, String status, String method) {
    }

    public boolean configured() {
        return config.configured();
    }

    public String keyId() {
        return config.keyId();
    }

    public Order createOrder(long amountPaise, String currency, String receipt, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", currency);
        body.put("receipt", receipt);
        body.put("notes", notes);
        JsonNode n = call("POST", "/v1/orders", mapper.writeValueAsString(body));
        return new Order(n.path("id").asString(), n.path("amount").asLong(), n.path("currency").asString(), n.path("status").asString());
    }

    public PaymentInfo fetchPayment(String paymentId) {
        JsonNode n = call("GET", "/v1/payments/" + paymentId, null);
        return info(n);
    }

    public PaymentInfo capture(String paymentId, long amountPaise, String currency) {
        JsonNode n = call("POST", "/v1/payments/" + paymentId + "/capture",
                mapper.writeValueAsString(Map.of("amount", amountPaise, "currency", currency)));
        return info(n);
    }

    /** Checkout signature: HMAC_SHA256(order_id + "|" + payment_id, key_secret), compared in constant time. */
    public boolean verifyCheckoutSignature(String orderId, String paymentId, String signature) {
        String expected = TokenHasher.hmacSha256Hex(config.keySecret(), orderId + "|" + paymentId);
        return TokenHasher.constantTimeEquals(expected, signature);
    }

    public boolean verifyWebhookSignature(String rawBody, String signature) {
        if (config.webhookSecret() == null || config.webhookSecret().isBlank() || signature == null) {
            return false;
        }
        String expected = TokenHasher.hmacSha256Hex(config.webhookSecret(), rawBody);
        return TokenHasher.constantTimeEquals(expected, signature);
    }

    static PaymentInfo info(JsonNode n) {
        return new PaymentInfo(n.path("id").asString(), n.path("order_id").asString(null), n.path("amount").asLong(),
                n.path("currency").asString(), n.path("status").asString(), n.path("method").asString(null));
    }

    private JsonNode call(String method, String path, String json) {
        if (!configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.PAYMENTS_UNAVAILABLE,
                    "Online payments are not configured yet. Please pay at the library desk.");
        }
        String auth = Base64.getEncoder().encodeToString((config.keyId() + ":" + config.keySecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + auth).header("Accept", "application/json");
        if (json == null) {
            b.GET();
        } else {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        try {
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Razorpay {} {} failed with HTTP {}", method, path, res.statusCode());
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PAYMENT_PROVIDER_ERROR,
                        "The payment provider could not process the request. Please try again.");
            }
            return mapper.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PAYMENT_PROVIDER_ERROR, "Payment provider request was interrupted.");
        } catch (Exception e) {
            log.warn("Razorpay {} {} error: {}", method, path, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.PAYMENT_PROVIDER_ERROR,
                    "Could not reach the payment provider. Please try again.");
        }
    }
}
