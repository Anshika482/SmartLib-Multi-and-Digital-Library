package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * The catalogue query as a reader actually sends it: a keyword, a shelf, or
 * both at once, one page at a time.
 *
 * <p>{@code GET /api/books} is the endpoint the catalogue screen is built on,
 * and its two filters are the part with a trap in it. They must combine with
 * <b>AND</b>: a keyword narrowing a shelf, not a keyword widening it back out
 * to the whole library. The fixtures below are arranged so the difference is
 * visible in a single number - nine books match the keyword, five sit on the
 * shelf, and only two are both. An OR would answer twelve.
 *
 * <p><b>Two libraries, deliberately alike.</b> Both hold books carrying the
 * same keyword and a shelf of the same name, so a query that lost its library
 * condition would return the neighbour's rows and inflate the totals rather
 * than failing quietly.
 *
 * <p><b>What a row may say.</b> A book with a cover reports the cover
 * <i>endpoint</i>, never the storage key the column holds, and no row carries a
 * library id. Both are asserted against the raw JSON, because a field that is
 * absent from a DTO today is only absent until somebody adds it.
 *
 * <p><b>Isolation:</b> the throwaway schema, never the development database,
 * with fresh libraries and a unique suffix per test. The property set is
 * character-for-character the one {@link BookCategoryPaginationIntegrationTest}
 * uses, so both classes share a single Spring context rather than starting a
 * second one and a second connection pool.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class CatalogueFilterIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step154-test-only-password";

    /** Books on the caller's first shelf, all matching the keyword. */
    private static final int FIRST_SHELF_MATCHING = 7;

    /** Books on the caller's second shelf that match the keyword. */
    private static final int SECOND_SHELF_MATCHING = 2;

    /** Books on the caller's second shelf that do not. */
    private static final int SECOND_SHELF_OTHER = 3;

    /** The neighbouring library's books, carrying the same keyword. */
    private static final int NEIGHBOUR_MATCHING = 3;

    private static final int MATCHING = FIRST_SHELF_MATCHING + SECOND_SHELF_MATCHING;

    private static final int SECOND_SHELF = SECOND_SHELF_MATCHING + SECOND_SHELF_OTHER;

    private static final int OWN_TOTAL = FIRST_SHELF_MATCHING + SECOND_SHELF;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;

    /** The keyword. Carries the suffix, so it cannot match another test's rows. */
    private String wanted;

    /** A second token, on the same shelves, that the keyword must not match. */
    private String unwanted;

    private String shelfName;
    private Long firstShelfId;
    private Long secondShelfId;
    private Long neighbourShelfId;
    private Long coveredBookId;
    private String coverKey;
    private String ownToken;
    private String neighbourToken;

    // ---------- fixtures ----------

    @BeforeEach
    void stockTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        wanted = "wanted" + suffix;
        unwanted = "other" + suffix;
        shelfName = "Step154 Shelf " + suffix;

        Library own = library("Own");
        Library neighbour = library("Neighbour");

        // The same shelf name in both libraries, so a lost library condition
        // cannot hide behind a category filter either.
        Category first = category(own, shelfName);
        Category second = category(own, "Step154 Second " + suffix);
        Category neighbourShelf = category(neighbour, shelfName);

        firstShelfId = first.getId();
        secondShelfId = second.getId();
        neighbourShelfId = neighbourShelf.getId();

        for (int n = 1; n <= FIRST_SHELF_MATCHING; n++) {
            book(own, first, wanted, n);
        }
        for (int n = 1; n <= SECOND_SHELF_MATCHING; n++) {
            book(own, second, wanted, 100 + n);
        }
        for (int n = 1; n <= SECOND_SHELF_OTHER; n++) {
            book(own, second, unwanted, 200 + n);
        }
        for (int n = 1; n <= NEIGHBOUR_MATCHING; n++) {
            book(neighbour, neighbourShelf, wanted, 300 + n);
        }

        // One book with a cover, to see what a row says about it. The key is
        // written straight onto the row: this test is about what the API
        // publishes, not about uploading, which BookCoverApiIntegrationTest
        // covers end to end.
        coverKey = "step154/" + suffix + "/not-a-real-object.webp";
        Book covered = book(own, first, wanted, 400);
        covered.setCoverImageKey(coverKey);
        coveredBookId = bookRepository.save(covered).getId();

        ownToken = login(member(own, "own"));
        neighbourToken = login(member(neighbour, "neighbour"));
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step154 " + label + " Library " + suffix);
        return libraryRepository.save(library);
    }

    private Category category(Library library, String name) {
        Category category = new Category();
        category.setName(name);
        category.setLibrary(library);
        return categoryRepository.save(category);
    }

    /**
     * One book whose title carries {@code token}, which is what the keyword
     * then matches on.
     */
    private Book book(Library library, Category shelf, String token, int n) {
        Book book = new Book();
        book.setTitle(String.format("Step154 %s Book %03d", token, n));
        book.setAuthor("Step154 Author " + suffix);
        book.setIsbn(String.format("154-%s-%03d", suffix, n));
        book.setTotalCopies(2);
        book.setAvailableCopies(2);
        book.setCategory(shelf);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User member(Library library, String label) {
        String username = "step154-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step154 " + label);
        user.setRole(Role.ROLE_MEMBER);
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

    /** The books endpoint, with a generous page so a filtered set fits in one. */
    private MockHttpServletRequestBuilder books() {
        return get("/api/books").param("size", "50").param("sortBy", "id").param("direction", "asc");
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode page(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = perform(request, token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.path("content").forEach(row -> titles.add(row.path("title").asText()));
        return titles;
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    // ---------- 1. keyword ----------

    @Test
    void aKeywordMatchesOnlyTheBooksThatCarryItInsideTheCallersLibrary() throws Exception {
        JsonNode matched = page(books().param("keyword", wanted), ownToken);

        // The covered book carries the keyword too, so it is one of these.
        assertThat(matched.path("totalElements").asLong()).isEqualTo(MATCHING + 1);
        assertThat(titles(matched)).allSatisfy(title -> assertThat(title).contains(wanted));
        assertThat(titles(matched)).noneSatisfy(title -> assertThat(title).contains(unwanted));
    }

    @Test
    void aKeywordMatchesTheAuthorAndTheIsbnAsWellAsTheTitle() throws Exception {
        // One author string and one ISBN fragment, both carrying the suffix and
        // neither appearing in any title.
        assertThat(page(books().param("keyword", "Step154 Author " + suffix), ownToken)
                .path("totalElements").asLong())
                .as("author")
                .isEqualTo(OWN_TOTAL + 1);

        assertThat(page(books().param("keyword", "154-" + suffix + "-001"), ownToken)
                .path("totalElements").asLong())
                .as("isbn")
                .isEqualTo(1);
    }

    @Test
    void anUnfilteredListIsTheCallersWholeLibraryAndNothingElse() throws Exception {
        JsonNode all = page(books(), ownToken);

        assertThat(all.path("totalElements").asLong()).isEqualTo(OWN_TOTAL + 1);
        assertThat(page(books(), neighbourToken).path("totalElements").asLong()).isEqualTo(NEIGHBOUR_MATCHING);
    }

    // ---------- 2. category ----------

    @Test
    void aShelfReturnsOnlyTheBooksOnIt() throws Exception {
        JsonNode shelf = page(books().param("categoryId", String.valueOf(secondShelfId)), ownToken);

        assertThat(shelf.path("totalElements").asLong()).isEqualTo(SECOND_SHELF);
        assertThat(shelf.path("content"))
                .allSatisfy(row -> assertThat(row.path("categoryId").asLong()).isEqualTo(secondShelfId));
        assertThat(shelf.path("content"))
                .allSatisfy(row -> assertThat(row.path("categoryName").asText())
                        .isEqualTo("Step154 Second " + suffix));
    }

    // ---------- 3. the two together ----------

    @Test
    void aKeywordAndAShelfNarrowEachOtherRatherThanAddingUp() throws Exception {
        JsonNode both = page(
                books().param("keyword", wanted).param("categoryId", String.valueOf(secondShelfId)),
                ownToken);

        // Nine match the keyword and five sit on the shelf. Two are both, and an
        // OR would have answered twelve.
        assertThat(both.path("totalElements").asLong())
                .as("the keyword must narrow the shelf, not widen it")
                .isEqualTo(SECOND_SHELF_MATCHING);

        assertThat(both.path("content")).allSatisfy(row -> {
            assertThat(row.path("categoryId").asLong()).isEqualTo(secondShelfId);
            assertThat(row.path("title").asText()).contains(wanted);
        });
    }

    @Test
    void aKeywordThatMatchesNothingOnThisShelfIsAnEmptyPageNotAnError() throws Exception {
        JsonNode none = page(
                books().param("keyword", unwanted).param("categoryId", String.valueOf(firstShelfId)),
                ownToken);

        // The token exists, and the shelf exists; nothing is both.
        assertThat(none.path("totalElements").asLong()).isZero();
        assertThat(none.path("totalPages").asInt()).isZero();
        assertThat(none.path("content")).isEmpty();
    }

    // ---------- 4. paging a filtered set ----------

    @Test
    void aFilteredSetIsPagedAndItsTotalsDescribeTheFilterNotTheLibrary() throws Exception {
        long total = MATCHING + 1;
        int size = 4;
        int totalPages = (int) Math.ceil(total / (double) size);

        List<Long> seen = new ArrayList<>();

        // One page past the last, which is a normal answer rather than an error.
        for (int number = 0; number <= totalPages; number++) {
            JsonNode slice = page(get("/api/books")
                    .param("keyword", wanted)
                    .param("categoryId", String.valueOf(firstShelfId))
                    .param("page", String.valueOf(number))
                    .param("size", String.valueOf(size))
                    .param("sortBy", "id")
                    .param("direction", "asc"), ownToken);

            assertThat(slice.path("page").asInt()).as("page").isEqualTo(number);
            assertThat(slice.path("size").asInt()).as("size").isEqualTo(size);
            assertThat(slice.path("totalElements").asLong())
                    .as("the total describes the filtered set")
                    .isEqualTo(FIRST_SHELF_MATCHING + 1);

            seen.addAll(ids(slice));
        }

        // Every matching row exactly once, and nothing twice.
        assertThat(seen).hasSize(FIRST_SHELF_MATCHING + 1).doesNotHaveDuplicates();
    }

    // ---------- 5. library isolation ----------

    @Test
    void theSameKeywordGivesEachLibraryItsOwnBooks() throws Exception {
        List<Long> ours = ids(page(books().param("keyword", wanted), ownToken));
        List<Long> theirs = ids(page(books().param("keyword", wanted), neighbourToken));

        assertThat(ours).isNotEmpty();
        assertThat(theirs).hasSize(NEIGHBOUR_MATCHING);
        assertThat(ours).doesNotContainAnyElementsOf(theirs);
    }

    @Test
    void aShelfBelongingToTheNeighbourIsNotFoundRatherThanEmpty() throws Exception {
        MvcResult result = perform(books().param("categoryId", String.valueOf(neighbourShelfId)), ownToken);

        // 404, the same answer a category id that never existed gets. An empty
        // page would have confirmed the shelf is real and merely out of reach.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aBookBelongingToTheNeighbourIsNotFoundByItsId() throws Exception {
        Long theirs = ids(page(books(), neighbourToken)).get(0);

        assertThat(perform(get("/api/books/" + theirs), ownToken).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(perform(get("/api/books/" + coveredBookId), neighbourToken).getResponse().getStatus())
                .as("and the same in the other direction")
                .isEqualTo(404);
    }

    // ---------- 6. one book ----------

    @Test
    void oneBookCarriesTheFactsTheDetailsScreenShows() throws Exception {
        MvcResult result = perform(get("/api/books/" + coveredBookId), ownToken);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        JsonNode book = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(book.path("title").asText()).contains(wanted);
        assertThat(book.path("author").asText()).isEqualTo("Step154 Author " + suffix);
        assertThat(book.path("isbn").asText()).isEqualTo(String.format("154-%s-400", suffix));
        assertThat(book.path("categoryId").asLong()).isEqualTo(firstShelfId);
        assertThat(book.path("categoryName").asText()).isEqualTo(shelfName);
        assertThat(book.path("totalCopies").asInt()).isEqualTo(2);
        assertThat(book.path("availableCopies").asInt()).isEqualTo(2);
    }

    // ---------- 7. covers ----------

    @Test
    void aRowPublishesTheCoverEndpointAndNeverTheStorageKey() throws Exception {
        MvcResult result = perform(get("/api/books/" + coveredBookId), ownToken);
        String json = result.getResponse().getContentAsString();
        JsonNode book = objectMapper.readTree(json);

        assertThat(book.path("hasCover").asBoolean()).isTrue();
        assertThat(book.path("coverUrl").asText()).isEqualTo("/api/books/" + coveredBookId + "/cover");

        // The column holds a storage key. The response must not, anywhere in it.
        assertThat(json).doesNotContain(coverKey);
        assertThat(json).doesNotContain("not-a-real-object.webp");
        assertThat(book.has("coverImageKey")).isFalse();
    }

    @Test
    void aBookWithNoCoverSaysSoRatherThanOfferingAUrlThatWouldFail() throws Exception {
        JsonNode plain = page(books().param("keyword", unwanted), ownToken);

        assertThat(plain.path("content")).isNotEmpty();
        assertThat(plain.path("content")).allSatisfy(row -> {
            assertThat(row.path("hasCover").asBoolean()).isFalse();
            assertThat(row.path("coverUrl").isNull()).isTrue();
        });
    }

    @Test
    void theCoverEndpointIsScopedToTheCallersLibraryLikeTheBookItself() throws Exception {
        // The row points at a key with no file behind it, so the owner gets 404
        // too - and that is the point. A neighbour asking for the same cover
        // must not be told anything the owner is not: not 403, which would
        // confirm the book is real, and not a different 404 body either.
        MvcResult owner = perform(get("/api/books/" + coveredBookId + "/cover"), ownToken);
        MvcResult neighbour = perform(get("/api/books/" + coveredBookId + "/cover"), neighbourToken);

        assertThat(owner.getResponse().getStatus()).as("a dangling key is not found, not a server error")
                .isEqualTo(404);
        assertThat(neighbour.getResponse().getStatus()).as("a neighbour may not read the cover")
                .isEqualTo(404);

        // Neither answer names the storage key.
        assertThat(owner.getResponse().getContentAsString()).doesNotContain(coverKey);
        assertThat(neighbour.getResponse().getContentAsString()).doesNotContain(coverKey);
    }

    // ---------- 8. what a row may not say ----------

    @Test
    void noRowCarriesALibraryIdOrAnythingElseInternal() throws Exception {
        String json = perform(books().param("keyword", wanted), ownToken)
                .getResponse().getContentAsString();

        assertThat(json).doesNotContain("libraryId");
        assertThat(json).doesNotContain("library_id");
        assertThat(json).doesNotContain("coverImageKey");
        assertThat(json).doesNotContain("password");

        JsonNode first = objectMapper.readTree(json).path("content").path(0);
        assertThat(first.has("library")).isFalse();

        // Exactly the ten fields BookResponse declares, and no eleventh.
        List<String> fields = new ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "id", "title", "author", "isbn", "categoryId", "categoryName",
                "totalCopies", "availableCopies", "hasCover", "coverUrl");
    }

    // ---------- 9. an empty catalogue ----------

    @Test
    void aLibraryWithNoBooksAnswersAnEmptyPageRatherThanAnError() throws Exception {
        Library empty = library("Empty");
        String token = login(member(empty, "empty"));

        JsonNode nothing = page(books(), token);

        assertThat(nothing.path("totalElements").asLong()).isZero();
        assertThat(nothing.path("totalPages").asInt()).isZero();
        assertThat(nothing.path("content")).isEmpty();

        // And a search of an empty library is still a successful search.
        JsonNode searched = page(books().param("keyword", wanted), token);
        assertThat(searched.path("totalElements").asLong()).isZero();
    }

    // ---------- 10. the catalogue needs a token ----------

    @Test
    void theCatalogueIsNotPublic() throws Exception {
        assertThat(mockMvc.perform(get("/api/books")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/books/" + coveredBookId)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/categories")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }
}
