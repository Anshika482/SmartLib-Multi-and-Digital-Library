package com.smartlib.payment;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;
import com.smartlib.security.TokenHasher;

/**
 * Built-in stand-in for a payment provider, for demos without Razorpay keys ({@code PAYMENTS_DEMO_MODE=true}). It mirrors
 * the Razorpay flow: an order id, a simulated checkout, and an HMAC signature over {@code orderId|paymentId} that only the
 * server can create and check, so the browser still cannot mark a fine paid on its own. No money moves; demo payments are
 * stored with provider {@value #PROVIDER} and labelled as demo in the UI. Real Razorpay keys always take precedence.
 */
@Component
public class DemoPaymentGateway {

    public static final String PROVIDER = "DEMO";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean enabled;
    private final String secret;

    public DemoPaymentGateway(AppProperties properties) {
        this.enabled = properties.razorpay().demoMode();
        String pepper = properties.security().tokenPepper();
        // Derived from the server-side pepper and never sent to the browser; random per run when no pepper is configured.
        this.secret = TokenHasher.hmacSha256Hex(pepper == null || pepper.isBlank() ? randomId(32) : pepper, "smartlib-demo-payments-v1");
    }

    public boolean enabled() {
        return enabled;
    }

    public String newOrderId() {
        return "demo_order_" + randomId(14);
    }

    public String newPaymentId() {
        return "demo_pay_" + randomId(14);
    }

    public String sign(String orderId, String paymentId) {
        return TokenHasher.hmacSha256Hex(secret, orderId + "|" + paymentId);
    }

    public boolean verify(String orderId, String paymentId, String signature) {
        return signature != null && TokenHasher.constantTimeEquals(sign(orderId, paymentId), signature);
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
