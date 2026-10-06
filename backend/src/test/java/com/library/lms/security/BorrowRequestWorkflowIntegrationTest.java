package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.Book;
import com.library.lms.entity.BorrowRequestStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BorrowRequestRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * The circulation workflow end to end: a member asks, the desk decides, a copy
 * is handed over, and it comes back.
 *
 * <p><b>Through HTTP, not the service.</b> Every step goes through the real
 * filter chain with a real token, so what is proven here includes the
 * authorization rules and not only the business ones. A member calling an
 * approve endpoint is refused by Spring Security before any code of this
 * feature runs, and that is worth asserting where it actually happens.
 *
 * <p><b>Two libraries, deliberately alike.</b> Both hold a book of the same
 * title and both have a member and a librarian, so a query that lost its
 * library condition would visibly act on the neighbour's rows.
 *
 * <p><b>Availability is the thing to watch.</b> Requesting does not move it.
 * Approving does not move it. Only issuing does, and only returning puts it
 * back - so each test that touches the workflow also asserts the count, which
 * is the number a real library would notice being wrong.
 *
 * <p><b>Isolation:</b> a schema of its own, created fresh. That matters here
 * beyond the usual reason: this feature appends four values to
 * {@code audit_events.action} and one to {@code target_type}, and
 * {@code ddl-auto=update} does not widen an ENUM that already exists. A fresh
 * schema has Hibernate build those columns from the Java enums, so the tests
 * below exercise the real audit writes rather than skipping them.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step155_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BorrowRequestWorkflowIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step155-test-only-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BorrowRequestRepository borrowRequestRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;

    private Library ourLibrary;
    private Long ourBookId;
    private Long secondBookId;
    private Long scarceBookId;
    private Long ourMemberId;

    private String memberToken;
    private String otherMemberToken;
    private String librarianToken;

    private Long neighbourBookId;
    private String neighbourMemberToken;
    private String neighbourLibrarianToken;

    // ---------- fixtures ----------

    @BeforeEach
    void stockTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        ourLibrary = library("Ours");
        Library neighbour = library("Neighbour");

        ourBookId = book(ourLibrary, "Ours", 3).getId();
        secondBookId = book(ourLibrary, "Second", 2).getId();
        scarceBookId = book(ourLibrary, "Scarce", 1).getId();
        neighbourBookId = book(neighbour, "Theirs", 3).getId();

        User member = member(ourLibrary, "member");
        ourMemberId = member.getId();

        memberToken = login(member);
        otherMemberToken = login(member(ourLibrary, "other"));
        librarianToken = login(staff(ourLibrary, "librarian", Role.ROLE_LIBRARIAN));

        neighbourMemberToken = login(member(neighbour, "nmember"));
        neighbourLibrarianToken = login(staff(neighbour, "nlibrarian", Role.ROLE_LIBRARIAN));
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step155 " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private Book book(Library library, String label, int copies) {
        Book book = new Book();
        book.setTitle("Step155 " + label + " " + suffix);
        book.setAuthor("Step155 Author");
        book.setIsbn("155-" + suffix + "-" + label);
        book.setTotalCopies(copies);
        book.setAvailableCopies(copies);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User member(Library library, String label) {
        return account(library, label, Role.ROLE_MEMBER);
    }

    private User staff(Library library, String label, Role role) {
        return account(library, label, role);
    }

    private User account(Library library, String label, Role role) {
        String username = "step155-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step155 " + label);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
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

    private MvcResult ask(Long bookId, String token) throws Exception {
        return perform(post("/api/borrow-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("bookId", bookId).toString()), token);
    }

    /** Asks for a book and returns the new request's id, failing if it was refused. */
    private Long askOk(Long bookId, String token) throws Exception {
        MvcResult result = ask(bookId, token);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private MvcResult act(Long requestId, String action, String token) throws Exception {
        return perform(post("/api/borrow-requests/" + requestId + "/" + action), token);
    }

    private MvcResult issue(Long requestId, String token, LocalDate dueDate) throws Exception {
        return perform(post("/api/borrow-requests/" + requestId + "/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("dueDate", dueDate.toString()).toString()), token);
    }

    private int availableCopies(Long bookId) {
        return bookRepository.findById(bookId).orElseThrow().getAvailableCopies();
    }

    private BorrowRequestStatus statusOf(Long requestId) {
        return borrowRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * How many of our library's audit events record this action.
     *
     * <p>Read through the repository's own library-scoped finder - it extends
     * {@code Repository} rather than {@code JpaRepository} deliberately, so
     * there is no unscoped {@code findAll} to reach for, in a test any more
     * than in production.</p>
     */
    private long auditCount(AuditAction action) {
        return auditEventRepository
                .findByLibraryId(ourLibrary.getId(), PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "id")))
                .getContent()
                .stream()
                .filter(event -> event.getAction() == action)
                .count();
    }

    // ---------- 1. the whole way through ----------

    @Test
    void requestApproveIssueReturnMovesTheCopyExactlyOnceInEachDirection() throws Exception {
        int before = availableCopies(ourBookId);

        Long requestId = askOk(ourBookId, memberToken);

        // Asking reserves nothing.
        assertThat(availableCopies(ourBookId)).as("requesting must not move stock").isEqualTo(before);
        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.REQUESTED);

        assertThat(act(requestId, "approve", librarianToken).getResponse().getStatus()).isEqualTo(200);

        // Nor does approving. This is the rule that keeps a request from making
        // a book unavailable to somebody standing at the desk.
        assertThat(availableCopies(ourBookId)).as("approving must not move stock either").isEqualTo(before);
        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.APPROVED);

        MvcResult issued = issue(requestId, librarianToken, LocalDate.now().plusDays(14));
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);

        assertThat(availableCopies(ourBookId)).as("issuing takes one copy").isEqualTo(before - 1);
        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.FULFILLED);

        long loanId = body(issued).path("id").asLong();

        // The request points at the loan it became.
        assertThat(borrowRequestRepository.findById(requestId).orElseThrow().getTransaction().getId())
                .isEqualTo(loanId);

        // The existing return path, untouched by this feature.
        MvcResult returned = perform(post("/api/transactions/" + loanId + "/return"), librarianToken);
        assertThat(returned.getResponse().getStatus()).isEqualTo(200);

        assertThat(availableCopies(ourBookId)).as("returning puts it back").isEqualTo(before);
        assertThat(body(returned).path("status").asText()).isEqualTo("RETURNED");

        // Returning does not reopen the request.
        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.FULFILLED);
    }

    @Test
    void aReturnedOnTimeLoanCarriesNoFine() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "approve", librarianToken);
        long loanId = body(issue(requestId, librarianToken, LocalDate.now().plusDays(7))).path("id").asLong();

        JsonNode returned = body(perform(post("/api/transactions/" + loanId + "/return"), librarianToken));

        // The existing overdue rules decide this, not anything added here.
        assertThat(returned.path("fineAmount").asDouble()).isZero();
        assertThat(returned.path("finePaymentStatus").asText()).isEqualTo("NOT_REQUIRED");
    }

    // ---------- 2. approval is not issue ----------

    @Test
    void approvingWritesNoLoan() throws Exception {
        long loansBefore = transactionRepository.count();

        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "approve", librarianToken);

        assertThat(transactionRepository.count()).as("approval must not create a loan").isEqualTo(loansBefore);
    }

    @Test
    void aRequestNobodyApprovedCannotBeIssued() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        int before = availableCopies(ourBookId);

        MvcResult result = issue(requestId, librarianToken, LocalDate.now().plusDays(7));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString()).contains("REQUESTED");
        assertThat(availableCopies(ourBookId)).isEqualTo(before);
    }

    // ---------- 3. every invalid transition ----------

    @Test
    void aDecidedRequestCannotBeDecidedAgain() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        assertThat(act(requestId, "approve", librarianToken).getResponse().getStatus()).isEqualTo(200);

        // Approving twice: the second is a refusal, not a silent success.
        assertThat(act(requestId, "approve", librarianToken).getResponse().getStatus()).isEqualTo(409);

        Long rejected = askOk(secondBookId, memberToken);
        assertThat(act(rejected, "reject", librarianToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(act(rejected, "reject", librarianToken).getResponse().getStatus()).isEqualTo(409);
        assertThat(act(rejected, "approve", librarianToken).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void anIssuedRequestCannotBeIssuedCancelledOrRejectedAgain() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "approve", librarianToken);
        int afterIssue = availableCopies(ourBookId) - 1;
        assertThat(issue(requestId, librarianToken, LocalDate.now().plusDays(7)).getResponse().getStatus())
                .isEqualTo(201);

        // A replayed issue is the double-issue case, and it must not take a
        // second copy off the shelf.
        assertThat(issue(requestId, librarianToken, LocalDate.now().plusDays(7)).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(act(requestId, "cancel", memberToken).getResponse().getStatus()).isEqualTo(409);
        assertThat(act(requestId, "reject", librarianToken).getResponse().getStatus()).isEqualTo(409);

        assertThat(availableCopies(ourBookId)).as("only one copy ever left the shelf").isEqualTo(afterIssue);
    }

    @Test
    void aMemberMayCancelWhileWaitingOrAfterApproval() throws Exception {
        Long waiting = askOk(ourBookId, memberToken);
        assertThat(act(waiting, "cancel", memberToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(waiting)).isEqualTo(BorrowRequestStatus.CANCELLED);

        Long approved = askOk(secondBookId, memberToken);
        act(approved, "approve", librarianToken);
        assertThat(act(approved, "cancel", memberToken).getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(approved)).isEqualTo(BorrowRequestStatus.CANCELLED);
    }

    @Test
    void aCancelledRequestIsFinished() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "cancel", memberToken);

        assertThat(act(requestId, "cancel", memberToken).getResponse().getStatus()).isEqualTo(409);
        assertThat(act(requestId, "approve", librarianToken).getResponse().getStatus()).isEqualTo(409);
        assertThat(issue(requestId, librarianToken, LocalDate.now().plusDays(7)).getResponse().getStatus())
                .isEqualTo(409);
    }

    // ---------- 4. duplicates ----------

    @Test
    void aMemberMayHoldOnlyOneLiveRequestPerBook() throws Exception {
        askOk(ourBookId, memberToken);

        MvcResult second = ask(ourBookId, memberToken);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString()).contains("already have an open request");
    }

    @Test
    void anApprovedRequestStillBlocksASecondOne() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "approve", librarianToken);

        assertThat(ask(ourBookId, memberToken).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void aFinishedRequestDoesNotBlockAskingAgain() throws Exception {
        Long cancelled = askOk(ourBookId, memberToken);
        act(cancelled, "cancel", memberToken);

        // The rule is about live requests, which is what makes it usable: a
        // member who changed their mind can change it back.
        assertThat(ask(ourBookId, memberToken).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void oneMembersRequestDoesNotBlockAnother() throws Exception {
        askOk(ourBookId, memberToken);

        assertThat(ask(ourBookId, otherMemberToken).getResponse().getStatus()).isEqualTo(201);
    }

    // ---------- 5. availability ----------

    @Test
    void approvingIsRefusedWhenEveryCopyIsOut() throws Exception {
        // The library owns one copy of this title; issue it to somebody else.
        Long taken = askOk(scarceBookId, otherMemberToken);
        act(taken, "approve", librarianToken);
        issue(taken, librarianToken, LocalDate.now().plusDays(7));
        assertThat(availableCopies(scarceBookId)).isZero();

        Long waiting = askOk(scarceBookId, memberToken);

        MvcResult result = act(waiting, "approve", librarianToken);
        assertThat(result.getResponse().getStatus()).as("nothing to approve against").isEqualTo(409);
        assertThat(statusOf(waiting)).as("and the request is left as it was").isEqualTo(BorrowRequestStatus.REQUESTED);
    }

    @Test
    void aBookWhoseCopiesAreAllOutCanStillBeQueuedFor() throws Exception {
        Long taken = askOk(scarceBookId, otherMemberToken);
        act(taken, "approve", librarianToken);
        issue(taken, librarianToken, LocalDate.now().plusDays(7));

        // Queuing for a book that is out is the whole point of a queue.
        assertThat(ask(scarceBookId, memberToken).getResponse().getStatus()).isEqualTo(201);
    }

    // ---------- 6. authorization ----------

    @Test
    void aMemberCannotDecideOnRequests() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);

        // Refused by the filter chain, before any of this feature's code runs.
        assertThat(act(requestId, "approve", memberToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(act(requestId, "reject", memberToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(issue(requestId, memberToken, LocalDate.now().plusDays(7)).getResponse().getStatus())
                .isEqualTo(403);

        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.REQUESTED);
    }

    @Test
    void aMemberCannotCancelSomebodyElsesRequest() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);

        MvcResult result = act(requestId, "cancel", otherMemberToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(statusOf(requestId)).isEqualTo(BorrowRequestStatus.REQUESTED);
    }

    @Test
    void aMemberCannotReadSomebodyElsesRequest() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);

        // Not-found rather than refused: the id tells them nothing.
        assertThat(perform(get("/api/borrow-requests/" + requestId), otherMemberToken)
                .getResponse().getStatus()).isEqualTo(404);

        // Staff of the same library may read it.
        assertThat(perform(get("/api/borrow-requests/" + requestId), librarianToken)
                .getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aMemberCannotReadTheLibrarysQueue() throws Exception {
        assertThat(perform(get("/api/borrow-requests"), memberToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(get("/api/borrow-requests"), librarianToken).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void staffDoNotQueueForBooks() throws Exception {
        MvcResult result = ask(ourBookId, librarianToken);

        // Staff issue at the desk. Answered as an eligibility refusal - the
        // existing MemberNotEligibleException, which the issue path already
        // maps to 400 - so this is the same answer a disabled or locked member
        // gets and the endpoint cannot be used to learn what role an account
        // holds. The status is the existing mapping's, not a new one.
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("LIBRARIAN");
    }

    @Test
    void noneOfThisIsPublic() throws Exception {
        assertThat(mockMvc.perform(get("/api/borrow-requests")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/borrow-requests/mine")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/borrow-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":1}"))
                .andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // ---------- 7. library isolation ----------

    @Test
    void aMemberCannotRequestTheNeighboursBook() throws Exception {
        long before = borrowRequestRepository.count();

        MvcResult result = ask(neighbourBookId, memberToken);

        // The same 404 a book id that never existed gets.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);

        // Counted as a delta: this schema keeps every row the class writes, so
        // an absolute count would be asserting how many tests ran before this
        // one rather than what this one did.
        assertThat(borrowRequestRepository.count()).as("nothing was written").isEqualTo(before);

        // And the member's own list is untouched.
        assertThat(body(perform(get("/api/borrow-requests/mine"), memberToken)).path("totalElements").asLong())
                .isZero();
    }

    @Test
    void staffCannotActOnTheNeighboursRequest() throws Exception {
        Long theirs = askOk(neighbourBookId, neighbourMemberToken);

        assertThat(perform(get("/api/borrow-requests/" + theirs), librarianToken)
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(act(theirs, "approve", librarianToken).getResponse().getStatus()).isEqualTo(404);
        assertThat(act(theirs, "reject", librarianToken).getResponse().getStatus()).isEqualTo(404);
        assertThat(issue(theirs, librarianToken, LocalDate.now().plusDays(7)).getResponse().getStatus())
                .isEqualTo(404);

        assertThat(statusOf(theirs)).as("untouched").isEqualTo(BorrowRequestStatus.REQUESTED);
    }

    @Test
    void eachLibrarysQueueHoldsOnlyItsOwn() throws Exception {
        askOk(ourBookId, memberToken);
        askOk(neighbourBookId, neighbourMemberToken);

        JsonNode ours = body(perform(get("/api/borrow-requests"), librarianToken));
        JsonNode theirs = body(perform(get("/api/borrow-requests"), neighbourLibrarianToken));

        assertThat(ours.path("totalElements").asLong()).isEqualTo(1);
        assertThat(theirs.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ours.path("content").path(0).path("id").asLong())
                .isNotEqualTo(theirs.path("content").path(0).path("id").asLong());
    }

    @Test
    void aMembersOwnListIsOnlyTheirOwn() throws Exception {
        askOk(ourBookId, memberToken);
        askOk(ourBookId, otherMemberToken);

        JsonNode mine = body(perform(get("/api/borrow-requests/mine"), memberToken));

        assertThat(mine.path("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.path("content").path(0).path("bookId").asLong()).isEqualTo(ourBookId);
    }

    // ---------- 8. what a row may say ----------

    @Test
    void aRowCarriesNoLibraryIdAndNoMemberId() throws Exception {
        askOk(ourBookId, memberToken);

        String json = perform(get("/api/borrow-requests"), librarianToken).getResponse().getContentAsString();

        assertThat(json).doesNotContain("libraryId");
        assertThat(json).doesNotContain("memberId");
        assertThat(json).doesNotContain("userId");
        assertThat(json).doesNotContain("password");
    }

    @Test
    void staffSeeWhoAskedAndAMemberDoesNotNeedTelling() throws Exception {
        askOk(ourBookId, memberToken);

        JsonNode queue = body(perform(get("/api/borrow-requests"), librarianToken));
        assertThat(queue.path("content").path(0).path("memberName").asText()).contains("member");

        JsonNode mine = body(perform(get("/api/borrow-requests/mine"), memberToken));
        assertThat(mine.path("content").path(0).path("memberName").isNull()).isTrue();
    }

    // ---------- 9. the audit trail ----------

    @Test
    void everyDecisionLeavesATrail() throws Exception {
        Long approved = askOk(ourBookId, memberToken);
        Long rejected = askOk(secondBookId, memberToken);
        Long cancelled = askOk(scarceBookId, memberToken);

        act(approved, "approve", librarianToken);
        act(rejected, "reject", librarianToken);
        act(cancelled, "cancel", memberToken);

        // These four values only exist because V11 appended them. On a schema
        // where the ENUM was not widened, these writes would fail - which is
        // exactly what this class's own fresh schema is for.
        assertThat(auditCount(AuditAction.REQUEST_CREATED)).isEqualTo(3);
        assertThat(auditCount(AuditAction.REQUEST_APPROVED)).isEqualTo(1);
        assertThat(auditCount(AuditAction.REQUEST_REJECTED)).isEqualTo(1);
        assertThat(auditCount(AuditAction.REQUEST_CANCELLED)).isEqualTo(1);
    }

    @Test
    void issuingARequestIsLoggedOnceAsALoanNotTwice() throws Exception {
        Long requestId = askOk(ourBookId, memberToken);
        act(requestId, "approve", librarianToken);

        long before = auditCount(AuditAction.BOOK_ISSUED);
        issue(requestId, librarianToken, LocalDate.now().plusDays(7));

        assertThat(auditCount(AuditAction.BOOK_ISSUED))
                .as("one hand-over is one event")
                .isEqualTo(before + 1);
    }

    // ---------- 10. concurrency ----------

    @Test
    void twoStaffIssuingTheLastCopyAtOnceProduceOneLoanNotTwo() throws Exception {
        // One copy, two members, two approved requests: both are issuable until
        // one of them actually is.
        Long first = askOk(scarceBookId, memberToken);
        Long second = askOk(scarceBookId, otherMemberToken);
        act(first, "approve", librarianToken);
        act(second, "approve", librarianToken);

        assertThat(availableCopies(scarceBookId)).isEqualTo(1);

        LocalDate due = LocalDate.now().plusDays(7);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Callable<Integer>> attempts = List.of(
                    attempt(first, due, go),
                    attempt(second, due, go));

            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> attempt : attempts) {
                futures.add(pool.submit(attempt));
            }

            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }

            // The invariant, not which thread won: a library with one copy
            // cannot lend it twice. Whether the loser saw a 409 from the state
            // check or a conflict from the optimistic lock on the book, the
            // shelf has to end up right.
            assertThat(statuses).as("exactly one hand-over succeeded").containsOnlyOnce(201);
            assertThat(availableCopies(scarceBookId)).as("the copy left the shelf once").isZero();

            long fulfilled = List.of(first, second).stream()
                    .filter(id -> statusOf(id) == BorrowRequestStatus.FULFILLED)
                    .count();
            assertThat(fulfilled).as("one request was fulfilled").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One issue attempt, held at the gate so both start together. */
    private Callable<Integer> attempt(Long requestId, LocalDate due, CountDownLatch go) {
        return () -> {
            go.await(10, TimeUnit.SECONDS);
            try {
                return issue(requestId, librarianToken, due).getResponse().getStatus();
            } catch (Exception failure) {
                // A conflict thrown rather than mapped still means this attempt
                // did not issue, which is all this test needs from it.
                return 500;
            }
        };
    }
}
