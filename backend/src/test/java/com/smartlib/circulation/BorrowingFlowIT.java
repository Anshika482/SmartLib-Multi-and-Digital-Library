package com.smartlib.circulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.smartlib.library.Library;
import com.smartlib.security.TokenHasher;
import com.smartlib.support.IntegrationTest;
import com.smartlib.support.StubServer;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.User;
import com.smartlib.user.UserRole;

import tools.jackson.databind.JsonNode;

/** Specification §80/§78: book + selected library + member -> request reaches ONLY that library -> issue -> return -> fine -> paid. */
class BorrowingFlowIT extends IntegrationTest {

    private Library a;
    private Library b;
    private Library c;
    private String librarianA;
    private String librarianB;
    private String librarianC;
    private User member;
    private String memberToken;
    private long bookId;

    @BeforeEach
    void fixture() throws Exception {
        stub.reset();
        a = data.library("LIBA", "Library A");
        b = data.library("LIBB", "Library B");
        c = data.library("LIBC", "Library C");
        librarianA = data.token(data.staff("la@example.com", UserRole.LIBRARIAN, a.getId(), ApprovalStatus.APPROVED));
        librarianB = data.token(data.staff("lb@example.com", UserRole.LIBRARIAN, b.getId(), ApprovalStatus.APPROVED));
        librarianC = data.token(data.staff("lc@example.com", UserRole.LIBRARIAN, c.getId(), ApprovalStatus.APPROVED));
        Map<String, Object> book = Map.of("title", "Clean Code", "authors", List.of("Robert C. Martin"));
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/holdings", librarianA, Map.of("isbn", "9780132350884", "book", book, "copies", 1)))
                .andExpect(status().isCreated());
        bookId = jdbc.queryForObject("SELECT id FROM books", Long.class);
        mvc.perform(postAuthed("/api/manage/libraries/" + b.getId() + "/holdings", librarianB, Map.of("bookId", bookId, "copies", 0)))
                .andExpect(status().isCreated());
        mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/holdings", librarianC, Map.of("bookId", bookId, "copies", 1)))
                .andExpect(status().isCreated());
        member = data.member("reader@example.com");
        memberToken = data.token(member);
    }

    @Test
    void requestFromSelectedLibrary_isRoutedOnlyToThatLibrary_andCompletesLifecycleWithPayment() throws Exception {
        JsonNode req = json(mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", c.getId())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.library.name").value("Library C"))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn());
        long requestId = req.get("id").asLong();

        // Library A's librarian does not see it and cannot act on it
        JsonNode aQueue = json(mvc.perform(getAuthed("/api/manage/libraries/" + a.getId() + "/requests?status=PENDING", librarianA)).andReturn());
        assertThat(aQueue.get("totalElements").asLong()).isZero();
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/requests/" + requestId + "/approve", librarianA, Map.of()))
                .andExpect(status().isNotFound());
        mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/requests/" + requestId + "/approve", librarianA, Map.of()))
                .andExpect(status().isForbidden());

        // Library C's librarian sees it, with the member and available copies
        JsonNode cQueue = json(mvc.perform(getAuthed("/api/manage/libraries/" + c.getId() + "/requests?status=PENDING", librarianC)).andReturn());
        assertThat(cQueue.get("totalElements").asLong()).isEqualTo(1);
        assertThat(cQueue.get("content").get(0).get("member").get("email").asString()).isEqualTo("reader@example.com");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications n JOIN staff_assignments s ON s.user_id = n.user_id"
                + " WHERE n.type = 'REQUEST_RECEIVED' AND s.library_id = ?", Long.class, c.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications n JOIN staff_assignments s ON s.user_id = n.user_id"
                + " WHERE n.type = 'REQUEST_RECEIVED' AND s.library_id = ?", Long.class, a.getId())).isZero();

        mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/requests/" + requestId + "/approve", librarianC, Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(jdbc.queryForObject("SELECT status FROM book_copies WHERE library_id = ?", String.class, c.getId())).isEqualTo("RESERVED");
        JsonNode loan = json(mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/requests/" + requestId + "/issue", librarianC, Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn());
        long loanId = loan.get("id").asLong();
        JsonNode mine = json(mvc.perform(getAuthed("/api/me/borrowings?filter=active", memberToken)).andReturn());
        assertThat(mine.get("content").get(0).get("library").get("name").asString()).isEqualTo("Library C");

        // Return 4 days late (default policy: Rs.5/day, no grace) -> Rs.20 fine
        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(18)));
        JsonNode returned = json(mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/borrowings/" + loanId + "/return", librarianC,
                Map.of("condition", "GOOD"))).andExpect(status().isOk()).andReturn());
        assertThat(returned.get("finesTotalPaise").asLong()).isEqualTo(2000);
        JsonNode fines = json(mvc.perform(getAuthed("/api/me/fines", memberToken)).andReturn());
        long fineId = fines.get(0).get("id").asLong();
        assertThat(fines.get(0).get("status").asString()).isEqualTo("UNPAID");

        // Razorpay: order is created server-side with the DB amount
        AtomicReference<String> orderBody = new AtomicReference<>();
        stub.on("/v1/orders", (ex, body) -> {
            orderBody.set(body);
            StubServer.json(ex, 200, "{\"id\":\"order_T1\",\"amount\":2000,\"currency\":\"INR\",\"status\":\"created\"}");
        });
        stub.on("/v1/payments/pay_T1", (ex, body) -> StubServer.json(ex, 200,
                "{\"id\":\"pay_T1\",\"order_id\":\"order_T1\",\"amount\":2000,\"currency\":\"INR\",\"status\":\"captured\",\"method\":\"upi\"}"));
        JsonNode order = json(mvc.perform(postAuthed("/api/me/fines/" + fineId + "/payment-order", memberToken, Map.of()))
                .andExpect(status().isOk()).andReturn());
        assertThat(order.get("orderId").asString()).isEqualTo("order_T1");
        assertThat(order.get("keyId").asString()).isEqualTo("rzp_test_key");
        assertThat(order.toString()).doesNotContain("test_secret");
        assertThat(orderBody.get()).contains("\"amount\":2000");

        // A forged signature is rejected and the fine stays unpaid
        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken, Map.of("razorpayOrderId", "order_T1",
                        "razorpayPaymentId", "pay_T1", "razorpaySignature", "deadbeef")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_VERIFICATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT status FROM fines WHERE id = ?", String.class, fineId)).isEqualTo("UNPAID");

        // Another member cannot verify someone else's order
        String other = data.token(data.member("other@example.com"));
        String signature = TokenHasher.hmacSha256Hex("test_secret", "order_T1|pay_T1");
        mvc.perform(postAuthed("/api/payments/razorpay/verify", other, Map.of("razorpayOrderId", "order_T1", "razorpayPaymentId", "pay_T1",
                "razorpaySignature", signature))).andExpect(status().isNotFound());

        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken, Map.of("razorpayOrderId", "order_T1", "razorpayPaymentId", "pay_T1",
                "razorpaySignature", signature))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT status FROM fines WHERE id = ?", String.class, fineId)).isEqualTo("PAID");
        // replay is idempotent
        mvc.perform(postAuthed("/api/payments/razorpay/verify", memberToken, Map.of("razorpayOrderId", "order_T1", "razorpayPaymentId", "pay_T1",
                "razorpaySignature", signature))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'PAYMENT_SUCCESS'", Long.class,
                member.getId())).isEqualTo(1);
        assertThat(mail.latestTo("reader@example.com").orElseThrow().subject()).contains("Payment receipt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_VERIFIED'", Long.class)).isEqualTo(1);
    }

    @Test
    void backendValidatesTheSelectedLibrary() throws Exception {
        Library d = data.library("LIBD", "Library D");
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", d.getId())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOK_NOT_IN_LIBRARY"));
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", b.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOK_UNAVAILABLE"));
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", 987654)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("LIBRARY_NOT_FOUND"));
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andExpect(status().isCreated());
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_ALREADY_EXISTS"));
        // staff accounts cannot borrow
        mvc.perform(postAuthed("/api/me/requests", librarianA, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andExpect(status().isForbidden());
        // unauthenticated
        mvc.perform(postJson("/api/me/requests", Map.of("bookId", bookId, "libraryId", a.getId()))).andExpect(status().isUnauthorized());
    }

    @Test
    void outstandingFinesBlockNewRequests_andWebhookMarksPaid() throws Exception {
        long fineId = insertFine(25_000);
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OUTSTANDING_FINES"));
        stub.on("/v1/orders", (ex, body) -> StubServer.json(ex, 200, "{\"id\":\"order_W1\",\"amount\":25000,\"currency\":\"INR\",\"status\":\"created\"}"));
        mvc.perform(postAuthed("/api/me/fines/" + fineId + "/payment-order", memberToken, Map.of())).andExpect(status().isOk());
        String payload = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_W1\",\"order_id\":\"order_W1\",\"amount\":25000,\"method\":\"card\"}}}}";
        mvc.perform(post("/api/webhooks/razorpay").contentType("application/json").content(payload).header("X-Razorpay-Signature", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/webhooks/razorpay").contentType("application/json").content(payload)
                .header("X-Razorpay-Signature", TokenHasher.hmacSha256Hex("test_webhook_secret", payload))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM fines WHERE id = ?", String.class, fineId)).isEqualTo("PAID");
        mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", a.getId()))).andExpect(status().isCreated());
    }

    @Test
    void approvalWithNoCopyFails_rejectReleases_cancelWorks() throws Exception {
        long r1 = json(mvc.perform(postAuthed("/api/me/requests", memberToken, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andReturn()).get("id").asLong();
        User second = data.member("second@example.com");
        String secondToken = data.token(second);
        long r2 = json(mvc.perform(postAuthed("/api/me/requests", secondToken, Map.of("bookId", bookId, "libraryId", a.getId())))
                .andReturn()).get("id").asLong();
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/requests/" + r1 + "/approve", librarianA, Map.of())).andExpect(status().isOk());
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/requests/" + r2 + "/approve", librarianA, Map.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_COPY_AVAILABLE"));
        mvc.perform(postAuthed("/api/me/requests/" + r1 + "/cancel", memberToken, Map.of())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM book_copies WHERE library_id = ?", String.class, a.getId())).isEqualTo("AVAILABLE");
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/requests/" + r2 + "/reject", librarianA, Map.of("reason", "Reserved for course reserve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        // the member cannot cancel someone else's request
        mvc.perform(postAuthed("/api/me/requests/" + r2 + "/cancel", memberToken, Map.of())).andExpect(status().isNotFound());
    }

    private long insertFine(long amount) {
        jdbc.update("INSERT INTO library_memberships (user_id, library_id, status, joined_at, updated_at) VALUES (?, ?, 'ACTIVE', NOW(6), NOW(6))",
                member.getId(), a.getId());
        jdbc.update("INSERT INTO fines (member_id, library_id, type, amount_paise, status, reason, created_at, updated_at) VALUES (?, ?, 'MANUAL', ?, 'UNPAID', 'Test', NOW(6), NOW(6))",
                member.getId(), a.getId(), amount);
        return jdbc.queryForObject("SELECT MAX(id) FROM fines", Long.class);
    }
}
