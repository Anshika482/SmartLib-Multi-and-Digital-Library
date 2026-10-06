package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
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
import com.library.lms.entity.Category;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.ReportRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Reports: who may read one, over what, and whether the figures are right.
 *
 * <p><b>The arithmetic is checked against fixtures counted by hand.</b> Each
 * library is stocked with a known number of loans on known dates, so every
 * assertion below is a number somebody worked out on paper rather than whatever
 * the query happened to return.
 *
 * <p><b>Scope is the thing most worth breaking.</b> Two libraries borrow the
 * same titles from shelves of the same name in the same weeks, so a report that
 * lost its library condition would not merely be wrong - it would look
 * plausible, with the neighbour's borrowing folded invisibly into the totals.
 * Every figure is therefore asserted for both libraries, and the super
 * administrator's is asserted to be the sum.
 *
 * <p><b>Isolation:</b> a schema of its own. A system-wide report counts every
 * row in the database, so sharing a schema with the other integration classes
 * would mean asserting against their fixtures too.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step158_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.datasource.hikari.maximum-pool-size=4",
        "library.fines.daily-rate=0.50"
})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReportIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step158-test-only-password";

    /** The window every assertion is made over. */
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

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
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String THROWAWAY_SCHEMA = "library_db_step158_it";

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String suffix;

    private String eastLibrarianToken;
    private String eastAdminToken;
    private String westLibrarianToken;
    private String memberToken;
    private String superAdminToken;

    private String eastTitle;

    /** Keeps each fixture's ISBN unique and inside the column's twenty characters. */
    private int isbnCounter;

    // ---------- fixtures ----------

    @BeforeEach
    void stockTwoLibraries() throws Exception {
        emptyTheSchema();

        suffix = UUID.randomUUID().toString().substring(0, 8);
        isbnCounter = 0;

        Library east = library("East");
        Library west = library("West");

        Category eastShelf = category(east, "Fiction " + suffix);
        Category westShelf = category(west, "Fiction " + suffix);

        Book eastBook = book(east, eastShelf, "East Popular");
        Book eastQuiet = book(east, eastShelf, "East Quiet");
        Book westBook = book(west, westShelf, "West Popular");

        eastTitle = eastBook.getTitle();

        User eastMember = account(east, "east-member", Role.ROLE_MEMBER);
        User westMember = account(west, "west-member", Role.ROLE_MEMBER);

        // ---- East, inside the window ----
        // 3 issues of the popular book in January, all returned in January.
        for (int day = 5; day <= 7; day++) {
            returned(east, eastMember, eastBook,
                    LocalDate.of(2026, 1, day), LocalDate.of(2026, 1, day + 10), 0.0);
        }
        // 1 issue of the quiet book in February, returned late owing 2.00.
        returned(east, eastMember, eastQuiet,
                LocalDate.of(2026, 2, 3), LocalDate.of(2026, 2, 20), 2.00);
        // 1 still out, issued in March and already overdue.
        open(east, eastMember, eastBook, LocalDate.of(2026, 3, 1), LocalDate.now().minusDays(3));

        // ---- East, outside the window: must not appear anywhere ----
        returned(east, eastMember, eastBook,
                LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 5), 9.99);
        returned(east, eastMember, eastBook,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), 7.77);

        // ---- West, inside the window ----
        // 2 issues, 1 return, so the two libraries cannot be confused.
        returned(west, westMember, westBook,
                LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 15), 1.50);
        open(west, westMember, westBook, LocalDate.of(2026, 2, 1), LocalDate.now().plusDays(10));

        eastLibrarianToken = login(account(east, "east-librarian", Role.ROLE_LIBRARIAN));
        eastAdminToken = login(account(east, "east-admin", Role.ROLE_ADMIN));
        westLibrarianToken = login(account(west, "west-librarian", Role.ROLE_LIBRARIAN));
        memberToken = login(eastMember);
        superAdminToken = login(account(east, "super", Role.ROLE_SUPER_ADMIN));
    }

    /**
     * Clears every row this class writes, before each test.
     *
     * <p>Needed because a system-wide report counts the whole database: without
     * this, "every library's issues" would include the fixtures of every test
     * that ran before, and the arithmetic below could not be checked against
     * numbers worked out on paper.</p>
     *
     * <p>The schema name is verified first. This deletes rows unconditionally,
     * so it must be impossible to point at anything but the throwaway schema
     * this class created - the same guard {@code FlywayMigrationIntegrationTest}
     * puts in front of its drop.</p>
     */
    private void emptyTheSchema() {
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        if (!THROWAWAY_SCHEMA.equals(schema)) {
            throw new IllegalStateException(
                    "Refusing to empty a schema this test did not create: " + schema);
        }

        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : List.of("payments", "audit_events", "borrow_requests", "transactions",
                    "digital_resources", "books", "categories", "refresh_tokens",
                    "password_reset_tokens", "users", "libraries")) {
                jdbcTemplate.execute("DELETE FROM " + table);
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step158 " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private Category category(Library library, String name) {
        Category category = new Category();
        category.setName(name);
        category.setLibrary(library);
        return categoryRepository.save(category);
    }

    private Book book(Library library, Category shelf, String label) {
        Book book = new Book();
        book.setTitle("Step158 " + label + " " + suffix);
        book.setAuthor("Step158 Author");
        // isbn is varchar(20), so this is a counter rather than the label: the
        // suffix alone is eight characters and the labels are long.
        book.setIsbn("158-" + suffix + "-" + (++isbnCounter));
        book.setTotalCopies(5);
        book.setAvailableCopies(5);
        book.setCategory(shelf);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User account(Library library, String label, Role role) {
        String username = "step158-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step158 " + label);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** A loan issued and returned on given dates, with a fine already fixed. */
    private void returned(Library library, User borrower, Book book, LocalDate issued, LocalDate back,
            double fine) {
        Transaction loan = new Transaction();
        loan.setLibrary(library);
        loan.setUser(borrower);
        loan.setBook(book);
        loan.setIssueDate(issued);
        loan.setDueDate(issued.plusDays(14));
        loan.setReturnDate(back);
        loan.setFineAmount(fine);
        loan.setStatus(TransactionStatus.RETURNED);
        loan.setFinePaymentStatus(fine > 0 ? FinePaymentStatus.UNPAID : FinePaymentStatus.NOT_REQUIRED);
        transactionRepository.save(loan);
    }

    /** A loan still out. */
    private void open(Library library, User borrower, Book book, LocalDate issued, LocalDate due) {
        Transaction loan = new Transaction();
        loan.setLibrary(library);
        loan.setUser(borrower);
        loan.setBook(book);
        loan.setIssueDate(issued);
        loan.setDueDate(due);
        loan.setStatus(TransactionStatus.ISSUED);
        transactionRepository.save(loan);
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

    private MockHttpServletRequestBuilder reports(LocalDate from, LocalDate to) {
        return get("/api/reports").param("from", from.toString()).param("to", to.toString());
    }

    private JsonNode report(String token) throws Exception {
        return report(token, FROM, TO);
    }

    private JsonNode report(String token, LocalDate from, LocalDate to) throws Exception {
        MvcResult result = perform(reports(from, to), token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ---------- 1. who may read one ----------

    @Test
    void aMemberGetsNoReport() throws Exception {
        assertThat(perform(reports(FROM, TO), memberToken).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void staffAndSuperAdministratorsDo() throws Exception {
        for (String token : List.of(eastLibrarianToken, eastAdminToken, superAdminToken)) {
            assertThat(perform(reports(FROM, TO), token).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void reportsAreNotPublic() throws Exception {
        assertThat(mockMvc.perform(reports(FROM, TO)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------- 2. library isolation ----------

    @Test
    void aLibrarianCountsTheirOwnLibraryAndNoOther() throws Exception {
        JsonNode east = report(eastLibrarianToken).path("totals");

        // Counted by hand: 3 + 1 + 1 issued inside the window. The two outside
        // it and everything in West are excluded.
        assertThat(east.path("issues").asLong()).isEqualTo(5);
        assertThat(east.path("returns").asLong()).isEqualTo(4);
    }

    @Test
    void theNeighbourCountsSomethingElseEntirely() throws Exception {
        JsonNode west = report(westLibrarianToken).path("totals");

        assertThat(west.path("issues").asLong()).isEqualTo(2);
        assertThat(west.path("returns").asLong()).isEqualTo(1);
    }

    @Test
    void anAdministratorSeesTheSameAsTheirLibrarian() throws Exception {
        assertThat(report(eastAdminToken).path("totals").path("issues").asLong())
                .isEqualTo(report(eastLibrarianToken).path("totals").path("issues").asLong());
    }

    @Test
    void aLibrarysReportNamesItsOwnLibraryAndIsNotSystemWide() throws Exception {
        JsonNode east = report(eastLibrarianToken);

        assertThat(east.path("systemWide").asBoolean()).isFalse();
        assertThat(east.path("libraryName").asText()).contains("East");
    }

    @Test
    void aBreakdownNeverNamesTheNeighboursBooks() throws Exception {
        String json = perform(reports(FROM, TO), eastLibrarianToken).getResponse().getContentAsString();

        assertThat(json).contains("East Popular");
        assertThat(json).doesNotContain("West Popular");
    }

    // ---------- 3. system-wide ----------

    @Test
    void aSuperAdministratorCountsEveryLibrary() throws Exception {
        JsonNode all = report(superAdminToken).path("totals");

        // East's 5 plus West's 2, and the same for returns: 4 plus 1.
        assertThat(all.path("issues").asLong()).isEqualTo(7);
        assertThat(all.path("returns").asLong()).isEqualTo(5);
    }

    @Test
    void aSystemWideReportBelongsToNoLibrary() throws Exception {
        JsonNode all = report(superAdminToken);

        assertThat(all.path("systemWide").asBoolean()).isTrue();
        assertThat(all.path("libraryName").isNull()).as("it is not about one library").isTrue();
    }

    @Test
    void aSystemWideBreakdownSpansBothLibraries() throws Exception {
        String json = perform(reports(FROM, TO), superAdminToken).getResponse().getContentAsString();

        assertThat(json).contains("East Popular");
        assertThat(json).contains("West Popular");
    }

    // ---------- 4. the dates ----------

    @Test
    void borrowingOutsideTheWindowIsNotCounted() throws Exception {
        // December and June exist in the fixtures precisely so they can be
        // excluded here. Their fines are 9.99 and 7.77, which would be
        // unmissable in the total.
        JsonNode inside = report(eastLibrarianToken).path("totals");

        assertThat(inside.path("issues").asLong()).isEqualTo(5);
        assertThat(inside.path("finesRaised").asDouble()).isEqualTo(2.00);
    }

    @Test
    void wideningTheWindowPicksTheOlderBorrowingUp() throws Exception {
        JsonNode wider = report(eastLibrarianToken, LocalDate.of(2025, 12, 1), LocalDate.of(2026, 7, 1))
                .path("totals");

        assertThat(wider.path("issues").asLong()).isEqualTo(7);
        // 2.00 + 9.99 + 7.77
        // Summed as doubles by the database, so 2.00 + 9.99 + 7.77 arrives as
        // 19.759999999999998. Compared with a tolerance rather than exactly.
        assertThat(wider.path("finesRaised").asDouble()).isCloseTo(19.76, within(0.001));
    }

    @Test
    void theRangeIncludesBothItsEndDays() throws Exception {
        // The earliest East issue is 5 January; a window starting that day must
        // include it, and one starting the next day must not.
        assertThat(report(eastLibrarianToken, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5))
                .path("totals").path("issues").asLong())
                .isEqualTo(1);

        assertThat(report(eastLibrarianToken, LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 6))
                .path("totals").path("issues").asLong())
                .isEqualTo(1);
    }

    @Test
    void anInvertedRangeIsRefusedRatherThanAnsweredWithZeroes() throws Exception {
        MvcResult result = perform(reports(TO, FROM), eastLibrarianToken);

        // Zero everything would read as "your library did nothing" rather than
        // "you asked backwards".
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("end date");
    }

    @Test
    void anAbsurdlyLongRangeIsRefused() throws Exception {
        MvcResult result = perform(
                reports(LocalDate.of(1990, 1, 1), LocalDate.of(2030, 1, 1)), eastLibrarianToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aMissingDateIsRefused() throws Exception {
        assertThat(perform(get("/api/reports").param("from", FROM.toString()), eastLibrarianToken)
                .getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    void aMemberIsRefusedWhateverDatesTheySend() throws Exception {
        // Authorization is decided before the dates, so an unauthorized caller
        // cannot tell a bad range from a refused one.
        assertThat(perform(reports(TO, FROM), memberToken).getResponse().getStatus()).isEqualTo(403);
    }

    // ---------- 5. the figures themselves ----------

    @Test
    void theHeadlineFiguresAreTheOnesCountedByHand() throws Exception {
        JsonNode totals = report(eastLibrarianToken).path("totals");

        assertThat(totals.path("totalBooks").asLong()).as("East holds two titles").isEqualTo(2);
        assertThat(totals.path("totalMembers").asLong()).as("one member").isEqualTo(1);
        assertThat(totals.path("activeLoans").asLong()).as("one still out").isEqualTo(1);
        assertThat(totals.path("overdueLoans").asLong()).as("and it is late").isEqualTo(1);
        // Not date-ranged, deliberately: what is owed is a fact about now, so
        // the December and June fines are in it too. 2.00 + 9.99 + 7.77.
        assertThat(totals.path("finesOutstanding").asDouble())
                .as("everything East still owes, whenever it arose")
                .isCloseTo(19.76, within(0.001));
    }

    @Test
    void whatIsOutNowIsNotBoundedByTheWindow() throws Exception {
        // The open loan was issued in March; a January-only window still reports
        // it as out, because "how many are out" is a fact about now.
        JsonNode january = report(eastLibrarianToken, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31))
                .path("totals");

        assertThat(january.path("issues").asLong()).isEqualTo(3);
        assertThat(january.path("activeLoans").asLong()).isEqualTo(1);
    }

    @Test
    void theMostIssuedTitleIsTheOneBorrowedMost() throws Exception {
        JsonNode top = report(eastLibrarianToken).path("mostIssued").path(0);

        assertThat(top.path("title").asText()).isEqualTo(eastTitle);
        // Three in January plus the one still out.
        assertThat(top.path("issues").asLong()).isEqualTo(4);
    }

    @Test
    void theShelfBreakdownCountsTheSameBorrowing() throws Exception {
        JsonNode shelf = report(eastLibrarianToken).path("categories").path(0);

        assertThat(shelf.path("category").asText()).isEqualTo("Fiction " + suffix);
        assertThat(shelf.path("issues").asLong()).isEqualTo(5);
    }

    @Test
    void circulationIsBrokenDownByMonthOldestFirst() throws Exception {
        JsonNode months = report(eastLibrarianToken).path("circulation");

        assertThat(months).hasSize(3);

        assertThat(months.path(0).path("month").asInt()).isEqualTo(1);
        assertThat(months.path(0).path("issues").asLong()).isEqualTo(3);
        assertThat(months.path(0).path("returns").asLong()).isEqualTo(3);

        assertThat(months.path(1).path("month").asInt()).isEqualTo(2);
        assertThat(months.path(1).path("issues").asLong()).isEqualTo(1);

        // March issued one and returned none - a month that would vanish from an
        // inner join between the two queries.
        assertThat(months.path(2).path("month").asInt()).isEqualTo(3);
        assertThat(months.path(2).path("issues").asLong()).isEqualTo(1);
        assertThat(months.path(2).path("returns").asLong()).isZero();
    }

    // ---------- 6. nothing private ----------

    @Test
    void noReportNamesAMemberOrCarriesAnInternalId() throws Exception {
        String json = perform(reports(FROM, TO), superAdminToken).getResponse().getContentAsString();

        assertThat(json).doesNotContain("east-member");
        assertThat(json).doesNotContain("@example.invalid");
        assertThat(json).doesNotContain("libraryId");
        assertThat(json).doesNotContain("userId");
        assertThat(json).doesNotContain("password");
    }

    // ---------- 7. an empty library ----------

    @Test
    void aLibraryThatHasDoneNothingReportsZeroesRatherThanFailing() throws Exception {
        Library quiet = library("Quiet");
        String token = login(account(quiet, "quiet-librarian", Role.ROLE_LIBRARIAN));

        JsonNode empty = report(token);
        JsonNode totals = empty.path("totals");

        assertThat(totals.path("issues").asLong()).isZero();
        assertThat(totals.path("returns").asLong()).isZero();
        assertThat(totals.path("finesRaised").asDouble()).isZero();
        assertThat(totals.path("finesOutstanding").asDouble()).isZero();
        assertThat(totals.path("paymentsTaken").asDouble()).isZero();

        // Empty lists, not nulls: a client renders "nothing yet" from a list it
        // can iterate, and null would be a second empty case to handle.
        assertThat(empty.path("mostIssued")).isEmpty();
        assertThat(empty.path("categories")).isEmpty();
        assertThat(empty.path("circulation")).isEmpty();
    }

    // ---------- 8. the scoping rule, as a rule ----------

    @Test
    void everyReportQueryIsEitherScopedOrSaysItIsNot() {
        // The same guarantee TransactionRepository carries, for the interface
        // that now holds the aggregates: a query is scoped to one library, or
        // its name says it spans all of them. There is no third kind, and the
        // system-wide list is exact - a new one fails here rather than
        // appearing quietly.
        List<Method> declared = Arrays.stream(ReportRepository.class.getDeclaredMethods()).toList();

        assertThat(declared)
                .filteredOn(method -> !method.getName().endsWith("SystemWide"))
                .as("every scoped aggregate names the library")
                .isNotEmpty()
                .allSatisfy(method -> assertThat(method.getName()).endsWith("AndLibraryId"));

        assertThat(declared)
                .extracting(Method::getName)
                .filteredOn(name -> name.endsWith("SystemWide"))
                .as("the system-wide aggregates are a closed set")
                .containsExactlyInAnyOrder(
                        "countIssuesSystemWide",
                        "countReturnsSystemWide",
                        "countActiveLoansSystemWide",
                        "countOverdueSystemWide",
                        "sumFinesRaisedSystemWide",
                        "sumFinesPaidSystemWide",
                        "sumOutstandingSystemWide",
                        "mostIssuedSystemWide",
                        "popularCategoriesSystemWide",
                        "issuesByMonthSystemWide",
                        "returnsByMonthSystemWide",
                        "overdueByMonthSystemWide");
    }
}
