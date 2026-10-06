package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * What is owed, who may see it, and the facts a loan carries about being late.
 *
 * <p>Two things are under test. The <b>fines list</b>, which is deliberately the
 * <i>payable</i> set: loans that came back owing money nobody has recorded as
 * paid. A book still out is accruing a fine that cannot be settled yet, so it
 * belongs to the overdue list rather than this one, and a test below insists on
 * that distinction rather than treating it as an accident.
 *
 * <p>And the <b>derived facts</b> a loan now carries - the book's title, and how
 * many days late it is. Both come from the server so that no screen has to
 * re-derive them: a client subtracting two dates itself would be a second copy
 * of the overdue rule, and the two would eventually disagree about what day it
 * is.
 *
 * <p><b>Isolation:</b> the throwaway schema, with the property set - daily rate
 * included - character-for-character the one {@link OverdueFineIntegrationTest}
 * uses, so the two share a single Spring context rather than starting a second.
 * The rate is 0.35 a day, so the arithmetic below is deliberately the kind that
 * a double would get wrong.
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
class OutstandingFinesIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step156-test-only-password";

    private static final double RATE = 0.35;

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

    private Library ourLibrary;
    private Book ourBook;
    private User ourMember;
    private User otherMember;

    private String memberToken;
    private String otherMemberToken;
    private String librarianToken;
    private String neighbourLibrarianToken;

    private Library neighbourLibrary;
    private User neighbourMember;
    private Book neighbourBook;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        ourLibrary = library("Ours");
        neighbourLibrary = library("Neighbour");

        ourBook = book(ourLibrary, "Ours");
        neighbourBook = book(neighbourLibrary, "Theirs");

        ourMember = account(ourLibrary, "member", Role.ROLE_MEMBER);
        otherMember = account(ourLibrary, "other", Role.ROLE_MEMBER);
        neighbourMember = account(neighbourLibrary, "nmember", Role.ROLE_MEMBER);

        memberToken = login(ourMember);
        otherMemberToken = login(otherMember);
        librarianToken = login(account(ourLibrary, "librarian", Role.ROLE_LIBRARIAN));
        neighbourLibrarianToken = login(account(neighbourLibrary, "nlibrarian", Role.ROLE_LIBRARIAN));
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step156 " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private Book book(Library library, String label) {
        Book book = new Book();
        book.setTitle("Step156 " + label + " Title " + suffix);
        book.setAuthor("Step156 " + label + " Author");
        book.setIsbn("156-" + suffix + "-" + label);
        book.setTotalCopies(5);
        book.setAvailableCopies(5);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User account(Library library, String label, Role role) {
        String username = "step156-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step156 " + label);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** A loan that came back late, with its fine fixed and in the given state. */
    private Transaction settledLoan(Library library, User borrower, Book book, int daysLate,
            FinePaymentStatus paymentStatus) {
        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(borrower);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now().minusDays(30));
        loan.setDueDate(LocalDate.now().minusDays(daysLate + 1));
        loan.setReturnDate(LocalDate.now().minusDays(1));
        loan.setFineAmount(daysLate * RATE);
        loan.setStatus(TransactionStatus.RETURNED);
        loan.setFinePaymentStatus(paymentStatus);
        if (paymentStatus == FinePaymentStatus.PAID) {
            loan.setFinePaidAt(LocalDateTime.now().minusHours(2));
        }
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
        loan.setReturnDate(null);
        loan.setFineAmount(null);
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

    private JsonNode fines(String token) throws Exception {
        MvcResult result = perform(get("/api/transactions/fines").param("size", "50"), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private JsonNode loan(Long id, String token) throws Exception {
        MvcResult result = perform(get("/api/transactions/" + id), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 1. the payable set ----------

    @Test
    void staffSeeTheirLibrarysUnpaidFines() throws Exception {
        Transaction unpaid = settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.UNPAID);

        assertThat(ids(fines(librarianToken))).contains(unpaid.getId());
    }

    @Test
    void aPaidFineIsNotStillOwed() throws Exception {
        Transaction paid = settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.PAID);

        assertThat(ids(fines(librarianToken))).doesNotContain(paid.getId());
    }

    @Test
    void aLoanThatCameBackOnTimeOwesNothingAndIsNotListed() throws Exception {
        Transaction onTime = settledLoan(ourLibrary, ourMember, ourBook, 0, FinePaymentStatus.NOT_REQUIRED);

        assertThat(ids(fines(librarianToken))).doesNotContain(onTime.getId());
    }

    @Test
    void anOpenOverdueLoanIsAccruingRatherThanPayableSoItIsNotOnThisList() throws Exception {
        Transaction stillOut = openLoan(ourLibrary, ourMember, ourBook, -5);

        // Not an oversight: recordFinePayment refuses an open loan, because its
        // fine is still growing. It belongs to the overdue list instead.
        assertThat(ids(fines(librarianToken))).doesNotContain(stillOut.getId());

        JsonNode overdue = objectMapper.readTree(perform(
                get("/api/transactions/status/OVERDUE").param("size", "50"), librarianToken)
                .getResponse().getContentAsString());
        assertThat(ids(overdue)).contains(stillOut.getId());
    }

    // ---------- 2. who may see what ----------

    @Test
    void aMemberSeesOnlyTheirOwnFines() throws Exception {
        Transaction theirs = settledLoan(ourLibrary, ourMember, ourBook, 4, FinePaymentStatus.UNPAID);
        Transaction somebodyElses = settledLoan(ourLibrary, otherMember, ourBook, 4, FinePaymentStatus.UNPAID);

        List<Long> mine = ids(fines(memberToken));

        assertThat(mine).contains(theirs.getId());
        assertThat(mine).doesNotContain(somebodyElses.getId());
    }

    @Test
    void oneMemberCannotSeeAnothersThroughThisEndpoint() throws Exception {
        settledLoan(ourLibrary, ourMember, ourBook, 4, FinePaymentStatus.UNPAID);

        // There is no id in the path and none accepted, so there is nothing to
        // substitute: the other member simply gets their own list, which is empty.
        assertThat(ids(fines(otherMemberToken))).isEmpty();
    }

    @Test
    void staffSeeBothMembersWhereAMemberSeesOne() throws Exception {
        Transaction first = settledLoan(ourLibrary, ourMember, ourBook, 2, FinePaymentStatus.UNPAID);
        Transaction second = settledLoan(ourLibrary, otherMember, ourBook, 6, FinePaymentStatus.UNPAID);

        assertThat(ids(fines(librarianToken))).contains(first.getId(), second.getId());
    }

    @Test
    void finesAreNotPublic() throws Exception {
        assertThat(mockMvc.perform(get("/api/transactions/fines")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // ---------- 3. library isolation ----------

    @Test
    void aFineStaysInsideItsOwnLibrary() throws Exception {
        Transaction ours = settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.UNPAID);
        Transaction theirs = settledLoan(neighbourLibrary, neighbourMember, neighbourBook, 3,
                FinePaymentStatus.UNPAID);

        List<Long> ourList = ids(fines(librarianToken));
        List<Long> theirList = ids(fines(neighbourLibrarianToken));

        assertThat(ourList).contains(ours.getId()).doesNotContain(theirs.getId());
        assertThat(theirList).contains(theirs.getId()).doesNotContain(ours.getId());
    }

    @Test
    void aNeighboursLoanCannotBeReadByItsId() throws Exception {
        Transaction theirs = settledLoan(neighbourLibrary, neighbourMember, neighbourBook, 3,
                FinePaymentStatus.UNPAID);

        assertThat(perform(get("/api/transactions/" + theirs.getId()), librarianToken)
                .getResponse().getStatus())
                .isEqualTo(404);
    }

    // ---------- 4. the derived facts ----------

    @Test
    void aLoanNamesItsBookSoAScreenNeedsNoSecondRequest() throws Exception {
        Transaction fine = settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.UNPAID);

        JsonNode row = loan(fine.getId(), librarianToken);

        assertThat(row.path("bookTitle").asText()).isEqualTo(ourBook.getTitle());
        assertThat(row.path("bookAuthor").asText()).isEqualTo(ourBook.getAuthor());
        assertThat(row.path("bookId").asLong()).isEqualTo(ourBook.getId());
    }

    @Test
    void aLoanDueTodayIsNotYetLate() throws Exception {
        Transaction dueToday = openLoan(ourLibrary, ourMember, ourBook, 0);

        JsonNode row = loan(dueToday.getId(), memberToken);

        assertThat(row.path("daysOverdue").asLong()).isZero();
        assertThat(row.path("status").asText()).isEqualTo("ISSUED");
        assertThat(row.path("fineAmount").isNull()).as("nothing owed, so no amount").isTrue();
    }

    @Test
    void aLoanOneDayPastDueIsOneDayLateAndOwesOneDay() throws Exception {
        Transaction late = openLoan(ourLibrary, ourMember, ourBook, -1);

        JsonNode row = loan(late.getId(), memberToken);

        assertThat(row.path("daysOverdue").asLong()).isEqualTo(1);
        assertThat(row.path("status").asText()).isEqualTo("OVERDUE");
        assertThat(row.path("fineAmount").asDouble()).isEqualTo(RATE);
        assertThat(row.path("finePaymentStatus").asText()).isEqualTo("UNPAID");
    }

    @Test
    void anOpenLoanFiveDaysLateOwesFiveDays() throws Exception {
        Transaction late = openLoan(ourLibrary, ourMember, ourBook, -5);

        JsonNode row = loan(late.getId(), memberToken);

        assertThat(row.path("daysOverdue").asLong()).isEqualTo(5);
        // 5 x 0.35 is exactly 1.75, which is the arithmetic a double gets wrong.
        assertThat(row.path("fineAmount").asDouble()).isEqualTo(1.75);
    }

    @Test
    void aReturnedLoanIsCountedToTheDayItCameBackNotToToday() throws Exception {
        // Due 4 days ago, back yesterday: three days late, and it stays three
        // however long ago that was.
        Transaction returned = settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.UNPAID);

        JsonNode row = loan(returned.getId(), librarianToken);

        assertThat(row.path("daysOverdue").asLong())
                .as("counted to the return date, so it stops growing")
                .isEqualTo(3);
        assertThat(row.path("status").asText()).isEqualTo("RETURNED");
    }

    @Test
    void readingTheSameLoanTwiceGivesTheSameAnswerAndWritesNothing() throws Exception {
        Transaction late = openLoan(ourLibrary, ourMember, ourBook, -3);
        Long version = transactionRepository.findById(late.getId()).orElseThrow().getVersion();

        JsonNode first = loan(late.getId(), memberToken);
        JsonNode second = loan(late.getId(), memberToken);

        assertThat(first.path("fineAmount").asDouble()).isEqualTo(second.path("fineAmount").asDouble());
        assertThat(first.path("daysOverdue").asLong()).isEqualTo(second.path("daysOverdue").asLong());

        // The row is untouched: a derived figure is worked out, never stored.
        Transaction after = transactionRepository.findById(late.getId()).orElseThrow();
        assertThat(after.getVersion()).as("no write").isEqualTo(version);
        assertThat(after.getFineAmount()).as("still nothing stored").isNull();
        assertThat(after.getStatus()).isEqualTo(TransactionStatus.ISSUED);
    }

    // ---------- 5. what a row may not say ----------

    @Test
    void aFineRowCarriesNoLibraryIdAndNoBorrowerDetails() throws Exception {
        settledLoan(ourLibrary, ourMember, ourBook, 3, FinePaymentStatus.UNPAID);

        String json = perform(get("/api/transactions/fines").param("size", "50"), librarianToken)
                .getResponse().getContentAsString();

        assertThat(json).doesNotContain("libraryId");
        assertThat(json).doesNotContain("password");
        assertThat(json).doesNotContain("@example.invalid");
    }
}
