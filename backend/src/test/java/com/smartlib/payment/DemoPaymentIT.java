package com.smartlib.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.smartlib.library.Library;
import com.smartlib.support.IntegrationTest;
import com.smartlib.user.User;

import tools.jackson.databind.JsonNode;

/** Demo gateway (no Razorpay keys, PAYMENTS_DEMO_MODE on): same server-side verification rules as Razorpay, no real money. */
@TestPropertySource(properties = {"smartlib.razorpay.key-id=", "smartlib.razorpay.key-secret=", "smartlib.razorpay.demo-mode=true"})
class DemoPaymentIT extends IntegrationTest {

    private User member;
    private String memberToken;
    private long fineId;

    @BeforeEach
    void fine() {
        Library lib = data.library("CEN", "Central Library");
        member = data.member("reader@example.com");
        memberToken = data.token(member);
        jdbc.update("INSERT INTO fines (member_id, library_id, type, amount_paise, status, reason, created_at, updated_at) VALUES (?, ?, 'MANUAL', 2500, 'UNPAID', 'Late return', NOW(6), NOW(6))",
                member.getId(), lib.getId());
        fineId = jdbc.queryForObject("SELECT MAX(id) FROM fines", Long.class);
    }

    private JsonNode order() throws Exception {
        return json(mvc.perform(postAuthed("/api/me/fines/" + fineId + "/payment-order", memberToken, Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("DEMO"))
                .andExpect(jsonPath("$.keyId").doesNotExist())
                .andExpect(jsonPath("$.amountPaise").value(2500)).andReturn());
    }

    private String fineStatus() {
        return jdbc.queryForObject("SELECT status FROM fines WHERE id = ?", String.class, fineId);
    }

    @Test
    void successfulDemoPaymentIsVerifiedByTheServerAndMarksTheFinePaid() throws Exception {
        String orderId = order().get("orderId").asString();
        assertThat(orderId).startsWith("demo_order_");

        // The browser cannot invent a signature
        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken,
                        Map.of("razorpayOrderId", orderId, "razorpayPaymentId", "demo_pay_fake", "razorpaySignature", "deadbeef")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        assertThat(fineStatus()).isEqualTo("UNPAID");

        // Another member cannot use this order
        String otherToken = data.token(data.member("other@example.com"));
        mvc.perform(postAuthed("/api/payments/demo/checkout", otherToken, Map.of("orderId", orderId, "method", "UPI", "succeed", true)))
                .andExpect(status().isNotFound());

        JsonNode auth = json(mvc.perform(postAuthed("/api/payments/demo/checkout", memberToken, Map.of("orderId", orderId, "method", "UPI", "succeed", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHORIZED")).andReturn());
        assertThat(fineStatus()).isEqualTo("UNPAID"); // not paid until /verify checks the signature

        Map<String, Object> verify = Map.of("razorpayOrderId", orderId, "razorpayPaymentId", auth.get("paymentId").asString(),
                "razorpaySignature", auth.get("signature").asString());
        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken, verify))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.provider").value("DEMO"))
                .andExpect(jsonPath("$.method").value("upi"));
        assertThat(fineStatus()).isEqualTo("PAID");
        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken, verify)).andExpect(jsonPath("$.status").value("PAID")); // idempotent
        mvc.perform(postAuthed("/api/me/fines/" + fineId + "/payment-order", memberToken, Map.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FINE_NOT_PAYABLE"));
    }

    @Test
    void declinedDemoPaymentLeavesTheFineUnpaidAndCanBeRetried() throws Exception {
        String first = order().get("orderId").asString();
        mvc.perform(postAuthed("/api/payments/demo/checkout", memberToken, Map.of("orderId", first, "method", "CARD", "succeed", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(fineStatus()).isEqualTo("UNPAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE provider_order_id = ?", String.class, first)).isEqualTo("FAILED");
        mvc.perform(postAuthed("/api/payments/demo/checkout", memberToken, Map.of("orderId", first, "method", "CARD", "succeed", true)))
                .andExpect(status().isConflict());

        String retry = order().get("orderId").asString();
        assertThat(retry).isNotEqualTo(first);
        mvc.perform(postAuthed("/api/payments/demo/checkout", memberToken, Map.of("orderId", retry, "method", "NETBANKING", "succeed", true)))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    }
}
