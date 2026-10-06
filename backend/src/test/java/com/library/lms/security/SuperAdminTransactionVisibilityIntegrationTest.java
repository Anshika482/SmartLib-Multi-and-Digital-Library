package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Book;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Who may read whose loans, across four roles and two libraries.
 *
 * <p>A super administrator's authority is the deployment's rather than one
 * library's - they approve the applications that create libraries - so bounding
 * their reads by the library on their own account showed them almost nothing.
 * This class proves the widening is exactly as wide as intended and no wider.
 *
 * <p><b>The boundary that must not move.</b> Every test that grants the super
 * administrator something has a twin that denies it to an administrator and a
 * librarian of one library looking at the other. The widening is a property of
 * one role, not of staff in general, and a change that leaked it to ADMIN would
 * fail here rather than in a customer's data.
 *
 * <p><b>Reading only.</b> Issuing, returning and recording a payment are desk
 * work and stay with ADMIN and LIBRARIAN; the last section insists on that, so
 * a future change cannot quietly turn a system-wide reader into a system-wide
 * writer.
 *
 * <p><b>Isolation:</b> the throwaway schema, with the property set - daily rate
 * included - character-for-character {@link OverdueFineIntegrationTest}'s, so
 * these share one Spring context rather than starting another.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "library.fines.daily-rate=0.35"
})
@AutoConfigureMockMvc
class SuperAdminTransactionVisibilityIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step157-test-only-password";

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
    private PasswordEncoder passwordEncoder;

    private String suffix;

    private User eastMember;
    private User westMember;
    private Book eastBook;
    private Book westBook;

    /** An unpaid fine in each library, and an open overdue loan in each. */
    private Transaction eastFine;
    private Transaction westFine;
    private Transaction eastOverdue;
    private Transaction westOverdue;

    private String superAdminToken;
    private String eastAdminToken;
    private String eastLibrarianToken;
    private String eastMemberToken;
    private String westMemberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void twoLibrariesEachOwingMoney() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library east = library("East");
        Library west = library("West");

        eastBook = book(east, "East");
        westBook = book(west, "West");

        eastMember = account(east, "east-member", Role.ROLE_MEMBER);
        westMember = account(west, "west-member", Role.ROLE_MEMBER);

        eastFine = settledLoan(east, eastMember, eastBook, 3);
        westFine = settledLoan(west, westMember, westBook, 5);
        eastOverdue = openLoan(east, eastMember, eastBook, -2);
        westOverdue = openLoan(west, westMember, westBook, -4);

        // The super administrator belongs to a library like any account, which
        // is exactly why the old scoped read showed them so little.
        superAdminToken = login(account(east, "super", Role.ROLE_SUPER_ADMIN));
        eastAdminToken = login(account(east, "east-admin", Role.ROLE_ADMIN));
        eastLibrarianToken = login(account(east, "east-librarian", Role.ROLE_LIBRARIAN));
        eastMemberToken = login(eastMember);
        westMemberToken = login(westMember);
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step157 " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private Book book(Library library, String label) {
        Book book = new Book();
        book.setTitle("Step157 " + label + " Title " + suffix);
        book.setAuthor("Step157 Author");
        book.setIsbn("157-" + suffix + "-" + label);
        book.setTotalCopies(4);
        book.setAvailableCopies(4);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User account(Library library, String label, Role role) {
        String username = "step157-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step157 " + label);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** A loan back late with an unpaid fine. */
    private Transaction settledLoan(Library library, User borrower, Book book, int daysLate) {
        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(borrower);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now().minusDays(30));
        loan.setDueDate(LocalDate.now().minusDays(daysLate + 1));
        loan.setReturnDate(LocalDate.now().minusDays(1));
        loan.setFineAmount(daysLate * 0.35);
        loan.setStatus(TransactionStatus.RETURNED);
        loan.setFinePaymentStatus(FinePaymentStatus.UNPAID);
        return transactionRepository.save(loan);
    }

    /** A loan still out, due in {@code dueInDays} - negative for one already late. */
    private Transaction openLoan(Library library, User borrower, Book book, int dueInDays) {
        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(borrower);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now().minusDays(20));
        loan.setDueDate(LocalDate.now().plusDays(dueInDays));
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

    private JsonNode okBody(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = perform(request, token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    /**
     * The fines list, newest first.
     *
     * <p>Ordered by id descending deliberately. This schema is shared with the
     * other integration classes and a system-wide read sees all of their rows
     * too, so a default ordering would push this test's fixtures - written
     * moments ago, and therefore the highest ids - off the first page. Sorting
     * newest first makes the assertion about what the query returns rather than
     * about how many rows happen to precede it.</p>
     */
    private MockHttpServletRequestBuilder fines() {
        return get("/api/transactions/fines")
                .param("size", "50").param("sortBy", "id").param("direction", "desc");
    }

    private MockHttpServletRequestBuilder overdue() {
        return get("/api/transactions/status/OVERDUE")
                .param("size", "50").param("sortBy", "id").param("direction", "desc");
    }

    // ---------- 1. the super administrator sees across libraries ----------

    @Test
    void aSuperAdministratorSeesOutstandingFinesInEveryLibrary() throws Exception {
        List<Long> seen = ids(okBody(fines(), superAdminToken));

        assertThat(seen).contains(eastFine.getId(), westFine.getId());
    }

    @Test
    void aSuperAdministratorSeesOverdueLoansInEveryLibrary() throws Exception {
        List<Long> seen = ids(okBody(overdue(), superAdminToken));

        assertThat(seen).contains(eastOverdue.getId(), westOverdue.getId());
    }

    @Test
    void aSuperAdministratorReadsAnyMembersHistoryWhateverLibrary() throws Exception {
        List<Long> theirs = ids(okBody(
                get("/api/transactions/user/" + westMember.getId())
                        .param("size", "50").param("sortBy", "id").param("direction", "desc"),
                superAdminToken));

        assertThat(theirs).contains(westFine.getId(), westOverdue.getId());
    }

    @Test
    void aSuperAdministratorOpensAnyLoanById() throws Exception {
        assertThat(perform(get("/api/transactions/" + westFine.getId()), superAdminToken)
                .getResponse().getStatus())
                .as("a loan they can see in a list must open")
                .isEqualTo(200);
    }

    @Test
    void aSuperAdministratorReadsAnyBooksHistory() throws Exception {
        List<Long> history = ids(okBody(
                get("/api/transactions/book/" + westBook.getId())
                        .param("size", "50").param("sortBy", "id").param("direction", "desc"),
                superAdminToken));

        assertThat(history).contains(westFine.getId(), westOverdue.getId());
    }

    // ---------- 2. an administrator does not ----------

    @Test
    void anAdministratorSeesOnlyTheirOwnLibrarysFines() throws Exception {
        List<Long> seen = ids(okBody(fines(), eastAdminToken));

        assertThat(seen).contains(eastFine.getId());
        assertThat(seen).as("the neighbour's fine is not theirs to see").doesNotContain(westFine.getId());
    }

    @Test
    void anAdministratorSeesOnlyTheirOwnLibrarysOverdueLoans() throws Exception {
        List<Long> seen = ids(okBody(overdue(), eastAdminToken));

        assertThat(seen).contains(eastOverdue.getId()).doesNotContain(westOverdue.getId());
    }

    @Test
    void anAdministratorCannotOpenANeighboursLoan() throws Exception {
        assertThat(perform(get("/api/transactions/" + westFine.getId()), eastAdminToken)
                .getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void anAdministratorGetsAnEmptyHistoryForANeighboursMember() throws Exception {
        // Scoped rather than refused: a user id from another library matches
        // nothing, which is the same answer an account that never borrowed gets.
        assertThat(ids(okBody(
                get("/api/transactions/user/" + westMember.getId()).param("size", "50"), eastAdminToken)))
                .isEmpty();
    }

    // ---------- 3. nor does a librarian ----------

    @Test
    void aLibrarianSeesOnlyTheirOwnLibrarysFines() throws Exception {
        List<Long> seen = ids(okBody(fines(), eastLibrarianToken));

        assertThat(seen).contains(eastFine.getId()).doesNotContain(westFine.getId());
    }

    @Test
    void aLibrarianCannotOpenANeighboursLoan() throws Exception {
        assertThat(perform(get("/api/transactions/" + westFine.getId()), eastLibrarianToken)
                .getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void aLibrarianGetsAnEmptyHistoryForANeighboursMember() throws Exception {
        assertThat(ids(okBody(
                get("/api/transactions/user/" + westMember.getId()).param("size", "50"), eastLibrarianToken)))
                .isEmpty();
    }

    // ---------- 4. nor does a member, of anyone ----------

    @Test
    void aMemberSeesOnlyTheirOwnFines() throws Exception {
        List<Long> seen = ids(okBody(fines(), eastMemberToken));

        assertThat(seen).contains(eastFine.getId()).doesNotContain(westFine.getId());
    }

    @Test
    void aMemberCannotAskForAnotherMembersHistory() throws Exception {
        // Refused outright, in their own library or another's: ownership is
        // decided before any row is read.
        assertThat(perform(get("/api/transactions/user/" + westMember.getId()), eastMemberToken)
                .getResponse().getStatus())
                .isEqualTo(403);

        assertThat(perform(get("/api/transactions/user/" + eastMember.getId()), westMemberToken)
                .getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void aMemberCannotOpenSomebodyElsesLoan() throws Exception {
        assertThat(perform(get("/api/transactions/" + westFine.getId()), eastMemberToken)
                .getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void aMemberCannotReadTheOverdueOrBookLists() throws Exception {
        assertThat(perform(overdue(), eastMemberToken).getResponse().getStatus()).isEqualTo(403);
        assertThat(perform(get("/api/transactions/book/" + eastBook.getId()), eastMemberToken)
                .getResponse().getStatus())
                .isEqualTo(403);
    }

    // ---------- 5. nothing here widens writing ----------

    @Test
    void aSuperAdministratorStillCannotIssueReturnOrSettleAFine() throws Exception {
        String issue = objectMapper.createObjectNode()
                .put("bookId", eastBook.getId())
                .put("memberId", eastMember.getId())
                .put("dueDate", LocalDate.now().plusDays(7).toString())
                .toString();

        // Desk work stays with ADMIN and LIBRARIAN. Reading everything is not
        // permission to do anything.
        assertThat(perform(post("/api/transactions/issue")
                .contentType(MediaType.APPLICATION_JSON).content(issue), superAdminToken)
                .getResponse().getStatus())
                .as("issuing")
                .isEqualTo(403);

        assertThat(perform(post("/api/transactions/" + eastOverdue.getId() + "/return"), superAdminToken)
                .getResponse().getStatus())
                .as("returning")
                .isEqualTo(403);

        assertThat(perform(post("/api/transactions/" + eastFine.getId() + "/fine-payment"), superAdminToken)
                .getResponse().getStatus())
                .as("recording a payment")
                .isEqualTo(403);
    }

    @Test
    void theWideningChangedNoFigureOnAnyLoan() throws Exception {
        // The same loan read by the two roles entitled to it says the same
        // thing. Widening who may look must not change what is seen.
        JsonNode bySuper = objectMapper.readTree(perform(
                get("/api/transactions/" + eastFine.getId()), superAdminToken)
                .getResponse().getContentAsString());
        JsonNode byAdmin = objectMapper.readTree(perform(
                get("/api/transactions/" + eastFine.getId()), eastAdminToken)
                .getResponse().getContentAsString());

        assertThat(bySuper.path("fineAmount").asDouble()).isEqualTo(byAdmin.path("fineAmount").asDouble());
        assertThat(bySuper.path("daysOverdue").asLong()).isEqualTo(byAdmin.path("daysOverdue").asLong());
        assertThat(bySuper.path("status").asText()).isEqualTo(byAdmin.path("status").asText());
        assertThat(bySuper.path("finePaymentStatus").asText())
                .isEqualTo(byAdmin.path("finePaymentStatus").asText());
    }

    // ---------- 6. the library is never taken from the request ----------

    @Test
    void noQueryParameterWidensAnAdministratorsView() throws Exception {
        // Whatever is appended, the library comes from the token. These are the
        // shapes an attacker would try first.
        Long theirLibrary = westMember.getLibrary().getId();

        List<String> attempts = List.of(
                "libraryId=" + theirLibrary,
                "library=" + theirLibrary,
                "userId=" + westMember.getId(),
                "allLibraries=true");

        for (String query : attempts) {
            List<Long> seen = ids(okBody(
                    get("/api/transactions/fines?size=50&" + query), eastAdminToken));

            assertThat(seen).as("with %s", query).doesNotContain(westFine.getId());
            assertThat(seen).as("and still their own, with %s", query).contains(eastFine.getId());
        }
    }
}
