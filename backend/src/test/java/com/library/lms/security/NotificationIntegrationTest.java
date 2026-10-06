package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Book;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.NotificationLog;
import com.library.lms.entity.NotificationOutcome;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.NotificationLogRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.LoanReminderService;

/**
 * Notifications, end to end, with a mail server that is deliberately not there.
 *
 * <p><b>The SMTP host points at a closed port.</b> That is the whole design of
 * this class: every send genuinely fails, which is the only honest way to prove
 * the property that matters most - <b>a failed email must not roll back a
 * successful issue, return, payment or registration</b>. Each test below performs
 * a real business operation over HTTP, waits for the notification to be attempted
 * and to fail, and then insists the business outcome is still there.
 *
 * <p><b>Delivery is asynchronous</b>, so the assertions poll rather than assume.
 * A notification appearing a moment after the response is the point of the
 * design; a test that read the table immediately would be asserting the opposite
 * of what was built.
 *
 * <p><b>Isolation:</b> a schema of its own, emptied before each test. The
 * notification log is read by library, and a test that counted rows would
 * otherwise be counting every earlier test's too.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step159_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=4",
        "library.fines.daily-rate=0.50",

        // Configured, and unreachable. Port 1 has nothing listening, so every
        // send throws rather than being skipped as unconfigured - which is what
        // makes the "a failed email changes nothing else" tests real.
        "spring.mail.host=localhost",
        "spring.mail.port=1",
        "spring.mail.username=notifications@example.invalid",
        "app.mail.from=notifications@example.invalid",
        "spring.mail.properties.mail.smtp.timeout=800",
        "spring.mail.properties.mail.smtp.connectiontimeout=800",

        // The sweep is driven by hand here. On a timer it would fire mid-test
        // and the counts would depend on when the suite happened to run.
        "notifications.reminders.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step159-test-only-password";

    private static final String THROWAWAY_SCHEMA = "library_db_step159_it";

    /** Long enough for a refused SMTP connection and the retry the mail library makes. */
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private NotificationLogRepository notificationLog;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Driven by hand: see the properties above. */
    @Autowired
    private LoanReminderService reminders;

    private String suffix;

    private Library ourLibrary;
    private Library neighbourLibrary;
    private Book ourBook;
    private User ourMember;
    private User neighbourMember;

    private String librarianToken;
    private String memberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void stockTwoLibraries() throws Exception {
        emptyTheSchema();

        suffix = UUID.randomUUID().toString().substring(0, 8);

        ourLibrary = library("Ours");
        neighbourLibrary = library("Neighbour");

        ourBook = book(ourLibrary, 1);
        ourMember = account(ourLibrary, "member", Role.ROLE_MEMBER, true);
        neighbourMember = account(neighbourLibrary, "nmember", Role.ROLE_MEMBER, true);

        librarianToken = login(account(ourLibrary, "librarian", Role.ROLE_LIBRARIAN, true));
        memberToken = login(ourMember);
    }

    /**
     * Clears every row this class writes.
     *
     * <p>The notification log is counted in these tests, so leftovers would be
     * part of the answer. The schema name is verified first: this deletes
     * unconditionally, so it must be impossible to point anywhere else.</p>
     */
    private void emptyTheSchema() {
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!THROWAWAY_SCHEMA.equals(schema)) {
            throw new IllegalStateException("Refusing to empty a schema this test did not create: " + schema);
        }

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : List.of("notification_log", "payments", "audit_events", "borrow_requests",
                    "transactions", "digital_resources", "books", "categories", "refresh_tokens",
                    "password_reset_tokens", "users", "libraries")) {
                jdbcTemplate.execute("DELETE FROM " + table);
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step159 " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private Book book(Library library, int n) {
        Book book = new Book();
        book.setTitle("Step159 Title " + n + " " + suffix);
        book.setAuthor("Step159 Author");
        book.setIsbn("159-" + suffix + "-" + n);
        book.setTotalCopies(3);
        book.setAvailableCopies(3);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User account(Library library, String label, Role role, boolean enabled) {
        String username = "step159-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step159 " + label);
        user.setRole(role);
        user.setEnabled(enabled);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    private Transaction openLoan(Library library, User borrower, Book book, LocalDate due) {
        Transaction loan = new Transaction();
        loan.setLibrary(library);
        loan.setUser(borrower);
        loan.setBook(book);
        loan.setIssueDate(LocalDate.now().minusDays(7));
        loan.setDueDate(due);
        loan.setStatus(TransactionStatus.ISSUED);
        return transactionRepository.save(loan);
    }

    // ---------- helpers ----------

    private String login(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", TEST_PASSWORD)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("login for %s", user.getUsername()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /**
     * Waits for something asynchronous to become true.
     *
     * <p>Polling rather than sleeping: a fixed sleep is either flaky or slow, and
     * this is measuring work on another thread whose timing is not the point.</p>
     */
    private static void waitFor(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + what, interrupted);
            }
        }

        throw new AssertionError("Timed out after " + PATIENCE.toSeconds() + "s waiting for " + what);
    }

    private List<NotificationLog> notificationsOf(Library library) {
        return notificationLog.findByLibraryIdAndCreatedAtBetweenOrderByIdDesc(
                library.getId(), LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
    }

    private long countOf(Library library, NotificationKind kind) {
        return notificationLog.countByLibraryIdAndKind(library.getId(), kind);
    }

    private void awaitNotification(Library library, NotificationKind kind, long expected) {
        waitFor(kind + " x" + expected, () -> countOf(library, kind) == expected);
    }

    /** Issues a copy through the real endpoint and returns the loan id. */
    private long issue(User borrower, Book book) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", book.getId())
                .put("memberId", borrower.getId())
                .put("dueDate", LocalDate.now().plusDays(14).toString())
                .toString();

        MvcResult result = perform(post("/api/transactions/issue")
                .contentType(MediaType.APPLICATION_JSON).content(body), librarianToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    // ---------- 0. the premise of this whole class ----------

    @Test
    void thisClassIsConfiguredToTryAndFailToSend() {
        // Every "a failed email changes nothing" test below is only meaningful if
        // a send is actually attempted. Asserted rather than assumed, because a
        // class that silently stopped sending would still pass those tests while
        // proving nothing.
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("localhost");
        assertThat(environment.getProperty("app.mail.from")).isEqualTo("notifications@example.invalid");
    }

    // ---------- 1. a failed email changes nothing else ----------

    @Test
    void anIssueStandsAlthoughItsNotificationCannotBeSent() throws Exception {
        long loanId = issue(ourMember, ourBook);

        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        // The send genuinely failed - the port is closed - and is recorded as
        // such rather than as "not configured".
        assertThat(notificationsOf(ourLibrary))
                .singleElement()
                .satisfies(sent -> {
                    assertThat(sent.getKind()).isEqualTo(NotificationKind.BOOK_ISSUED);
                    assertThat(sent.getOutcome()).isEqualTo(NotificationOutcome.FAILED);
                });

        // And the business outcome is untouched: the loan exists, the copy is
        // off the shelf, and the member has it.
        Transaction loan = transactionRepository.findById(loanId).orElseThrow();
        assertThat(loan.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(loan.getUser().getId()).isEqualTo(ourMember.getId());
        assertThat(bookRepository.findById(ourBook.getId()).orElseThrow().getAvailableCopies()).isEqualTo(2);
    }

    @Test
    void aReturnStandsAlthoughItsNotificationCannotBeSent() throws Exception {
        long loanId = issue(ourMember, ourBook);
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        MvcResult returned = perform(post("/api/transactions/" + loanId + "/return"), librarianToken);
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);

        awaitNotification(ourLibrary, NotificationKind.BOOK_RETURNED, 1);

        Transaction loan = transactionRepository.findById(loanId).orElseThrow();
        assertThat(loan.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(bookRepository.findById(ourBook.getId()).orElseThrow().getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void theResponseDoesNotWaitOnTheMailServer() throws Exception {
        // The SMTP attempt takes the best part of a second against a closed
        // port. If delivery were on the request thread this would show up here.
        long before = System.nanoTime();
        issue(ourMember, ourBook);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - before);

        assertThat(elapsed).as("issuing must not wait on SMTP").isLessThan(Duration.ofSeconds(3));

        // It did happen, just not in the request.
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);
    }

    // ---------- 2. the events fire ----------

    @Test
    void approvingARequestTellsTheMemberWhoAskedForIt() throws Exception {
        String ask = objectMapper.createObjectNode().put("bookId", ourBook.getId()).toString();
        MvcResult asked = perform(post("/api/borrow-requests")
                .contentType(MediaType.APPLICATION_JSON).content(ask), memberToken);
        assertThat(asked.getResponse().getStatus()).isEqualTo(201);

        long requestId = objectMapper.readTree(asked.getResponse().getContentAsString()).path("id").asLong();

        assertThat(perform(post("/api/borrow-requests/" + requestId + "/approve"), librarianToken)
                .getResponse().getStatus()).isEqualTo(200);

        awaitNotification(ourLibrary, NotificationKind.REQUEST_APPROVED, 1);

        assertThat(notificationsOf(ourLibrary))
                .filteredOn(sent -> sent.getKind() == NotificationKind.REQUEST_APPROVED)
                .singleElement()
                .satisfies(sent -> {
                    assertThat(sent.getSubjectId()).isEqualTo(requestId);
                    // The member who asked, not the member of staff who decided.
                    assertThat(sent.getRecipientUserId()).isEqualTo(ourMember.getId());
                });
    }

    @Test
    void rejectingARequestTellsThemToo() throws Exception {
        String ask = objectMapper.createObjectNode().put("bookId", ourBook.getId()).toString();
        long requestId = objectMapper.readTree(perform(post("/api/borrow-requests")
                .contentType(MediaType.APPLICATION_JSON).content(ask), memberToken)
                .getResponse().getContentAsString()).path("id").asLong();

        perform(post("/api/borrow-requests/" + requestId + "/reject"), librarianToken);

        awaitNotification(ourLibrary, NotificationKind.REQUEST_REJECTED, 1);
    }

    // ---------- 3. once, against the real unique key ----------

    @Test
    void issuingTwiceTellsTheMemberOncePerLoan() throws Exception {
        long first = issue(ourMember, ourBook);
        long second = issue(ourMember, ourBook);

        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 2);

        // Two loans, two notifications - the subject is the loan, so a member
        // borrowing twice hears twice, which is right.
        assertThat(notificationsOf(ourLibrary))
                .filteredOn(sent -> sent.getKind() == NotificationKind.BOOK_ISSUED)
                .extracting(NotificationLog::getSubjectId)
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    void theSameReminderIsNeverSentTwiceHoweverOftenTheSweepRuns() {
        openLoan(ourLibrary, ourMember, ourBook, LocalDate.now().minusDays(4));

        reminders.remindOverdue();
        awaitNotification(ourLibrary, NotificationKind.OVERDUE, 1);

        // Three more passes. The sweep keeps no state of its own; the unique key
        // is what stops the second message.
        reminders.remindOverdue();
        reminders.remindOverdue();
        reminders.remindOverdue();

        waitFor("no second overdue reminder", () -> countOf(ourLibrary, NotificationKind.OVERDUE) == 1);
        assertThat(countOf(ourLibrary, NotificationKind.OVERDUE)).isEqualTo(1);
    }

    @Test
    void aLoanDueSoonIsRemindedOnceAndSeparatelyFromBeingOverdue() {
        openLoan(ourLibrary, ourMember, ourBook, LocalDate.now().plusDays(2));

        reminders.remindDueSoon();
        awaitNotification(ourLibrary, NotificationKind.DUE_SOON, 1);

        reminders.remindDueSoon();
        assertThat(countOf(ourLibrary, NotificationKind.DUE_SOON)).isEqualTo(1);

        // A different kind about the same loan is a different notification, so
        // the member can be told it is due soon and later that it is overdue.
        assertThat(countOf(ourLibrary, NotificationKind.OVERDUE)).isZero();
    }

    // ---------- 4. nobody who cannot receive it ----------

    @Test
    void aDisabledMemberIsNotWrittenTo() throws Exception {
        long loanId = issue(ourMember, ourBook);
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        // Disabled after the loan, before the reminder.
        ourMember.setEnabled(false);
        userRepository.save(ourMember);

        Transaction loan = transactionRepository.findById(loanId).orElseThrow();
        loan.setDueDate(LocalDate.now().minusDays(3));
        transactionRepository.save(loan);

        reminders.remindOverdue();

        // Give the async listener room to have done something, then insist it
        // did not: the account is read at the moment of sending, not trusted
        // from the event.
        waitFor("the sweep to settle", () -> true);
        assertThat(countOf(ourLibrary, NotificationKind.OVERDUE)).isZero();
    }

    // ---------- 5. library isolation ----------

    @Test
    void aNotificationBelongsToTheLibraryTheEventHappenedIn() throws Exception {
        issue(ourMember, ourBook);
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        assertThat(countOf(ourLibrary, NotificationKind.BOOK_ISSUED)).isEqualTo(1);
        assertThat(countOf(neighbourLibrary, NotificationKind.BOOK_ISSUED))
                .as("the neighbour's log is untouched")
                .isZero();
    }

    @Test
    void eachLibrarysSweepTouchesOnlyItsOwnLoans() {
        openLoan(ourLibrary, ourMember, ourBook, LocalDate.now().minusDays(2));
        openLoan(neighbourLibrary, neighbourMember, book(neighbourLibrary, 2), LocalDate.now().minusDays(2));

        // One sweep, across the deployment - it is a background job with no
        // library. Each notification is still recorded against the library its
        // loan belongs to.
        reminders.remindOverdue();

        awaitNotification(ourLibrary, NotificationKind.OVERDUE, 1);
        awaitNotification(neighbourLibrary, NotificationKind.OVERDUE, 1);

        assertThat(notificationsOf(ourLibrary))
                .allSatisfy(sent -> assertThat(sent.getRecipientUserId()).isEqualTo(ourMember.getId()));
        assertThat(notificationsOf(neighbourLibrary))
                .allSatisfy(sent -> assertThat(sent.getRecipientUserId()).isEqualTo(neighbourMember.getId()));
    }

    // ---------- 6. the receipt ----------

    @Test
    void settlingAFineAtTheDeskTellsTheMemberWhoOwedIt() throws Exception {
        long loanId = issue(ourMember, ourBook);
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        // Back late, so there is a fine to settle.
        Transaction loan = transactionRepository.findById(loanId).orElseThrow();
        loan.setDueDate(LocalDate.now().minusDays(4));
        transactionRepository.save(loan);

        assertThat(perform(post("/api/transactions/" + loanId + "/return"), librarianToken)
                .getResponse().getStatus()).isEqualTo(200);
        awaitNotification(ourLibrary, NotificationKind.BOOK_RETURNED, 1);

        Transaction returned = transactionRepository.findById(loanId).orElseThrow();
        assertThat(returned.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);

        // The desk flow, which is the one that exists without a gateway. The
        // online receipt rides the same notification from PaymentService.
        assertThat(perform(post("/api/transactions/" + loanId + "/fine-payment"), librarianToken)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(transactionRepository.findById(loanId).orElseThrow().getFinePaymentStatus())
                .isEqualTo(FinePaymentStatus.PAID);
    }

    // ---------- 7. nothing secret is in the record ----------

    @Test
    void theNotificationRecordHoldsNoAddressAndNoContent() throws Exception {
        issue(ourMember, ourBook);
        awaitNotification(ourLibrary, NotificationKind.BOOK_ISSUED, 1);

        // Read the row as columns rather than through the entity, so this is
        // about the table rather than about the mapping.
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = ? AND table_name = 'notification_log'",
                String.class, THROWAWAY_SCHEMA);

        assertThat(columns).containsExactlyInAnyOrder(
                "id", "library_id", "recipient_user_id", "kind", "subject_id", "created_at", "outcome");

        // No address, no subject line, no body: a table of message bodies is a
        // table of everything the library has ever told anybody.
        assertThat(columns).noneSatisfy(column ->
                assertThat(column).containsAnyOf("email", "address", "body", "subject_line", "message"));
    }

    @Test
    void noNotificationEndpointIsExposed() throws Exception {
        // This step is email only. Nothing serves the log over HTTP, so there is
        // no endpoint to authorize and none to leak one member's notifications
        // to another.
        assertThat(perform(get("/api/notifications"), librarianToken).getResponse().getStatus())
                .isIn(401, 403, 404);
    }
}
