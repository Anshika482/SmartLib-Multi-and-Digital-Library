package com.smartlib.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.smartlib.library.Library;
import com.smartlib.support.CatalogueFixture;
import com.smartlib.support.IntegrationTest;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.UserRole;

import tools.jackson.databind.JsonNode;

/** Specification §79: search must span ALL libraries; the library filter only restricts; clearing restores everything. */
class CatalogueSearchIT extends IntegrationTest {

    private Library a;
    private Library b;
    private Library c;
    private long technology;

    @BeforeEach
    void fixture() throws Exception {
        a = data.library("LIBA", "Library A");
        b = data.library("LIBB", "Library B");
        c = data.library("LIBC", "Library C");
        technology = jdbc.queryForObject("SELECT id FROM categories WHERE slug = 'technology'", Long.class);
        addBooks(a, "A", 10);
        addBooks(b, "B", 8);
        addBooks(c, "C", 5);
        String tokenA = token(a);
        add(tokenA, a, Map.of("title", "Cooking Basics", "authors", List.of("Chef Ram")), 1, null);
        add(token(b), b, Map.of("title", "History of Art", "authors", List.of("Ana Lopez")), 1, null);
    }

    private String token(Library lib) {
        String email = "lib-" + lib.getCode().toLowerCase() + "@example.com";
        Long existing = jdbc.query("SELECT id FROM users WHERE email = ?", rs -> rs.next() ? rs.getLong(1) : null, email);
        if (existing != null) {
            return data.token(jdbcUser(existing));
        }
        return data.token(data.staff(email, UserRole.LIBRARIAN, lib.getId(), ApprovalStatus.APPROVED));
    }

    private com.smartlib.user.User jdbcUser(long id) {
        com.smartlib.user.User u = new com.smartlib.user.User();
        u.setId(id);
        return u;
    }

    private void addBooks(Library lib, String tag, int n) throws Exception {
        String token = token(lib);
        for (int i = 1; i <= n; i++) {
            add(token, lib, Map.of("title", "Java Programming " + tag + i, "authors", List.of("Author " + tag + i), "categoryId", technology),
                    i % 3 == 0 ? 0 : 2, i == 1 ? CatalogueFixture.isbn13(lib.getId().intValue() * 100 + i) : null);
        }
    }

    private void add(String token, Library lib, Map<String, Object> book, int copies, String isbn) throws Exception {
        Map<String, Object> b = new java.util.HashMap<>(book);
        if (isbn != null) {
            b.put("isbn", isbn);
        }
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/holdings", token, Map.of("book", b, "copies", copies)))
                .andExpect(status().isCreated());
    }

    private JsonNode search(String query) throws Exception {
        return json(mvc.perform(get("/api/public/catalogue" + query)).andExpect(status().isOk()).andReturn());
    }

    private Set<String> libraryNames(JsonNode page) {
        Set<String> names = new HashSet<>();
        for (JsonNode item : page.get("content")) {
            for (JsonNode h : item.get("holdings")) {
                names.add(h.get("libraryName").asString());
            }
        }
        return names;
    }

    @Test
    void searchSpansAllLibraries_filterRestricts_clearingRestores() throws Exception {
        JsonNode all = search("?q=java&size=48");
        assertThat(all.get("totalElements").asLong()).isEqualTo(23);
        assertThat(libraryNames(all)).containsExactlyInAnyOrder("Library A", "Library B", "Library C");

        JsonNode onlyB = search("?q=java&libraryId=" + b.getId() + "&size=48");
        assertThat(onlyB.get("totalElements").asLong()).isEqualTo(8);
        assertThat(libraryNames(onlyB)).containsExactly("Library B");

        JsonNode back = search("?q=java&size=48");
        assertThat(back.get("totalElements").asLong()).isEqualTo(23);

        JsonNode everything = search("?size=48");
        assertThat(everything.get("totalElements").asLong()).isEqualTo(25);
        assertThat(libraryNames(everything)).containsExactlyInAnyOrder("Library A", "Library B", "Library C");
    }

    @Test
    void paginationIsIndependentAndReportsTotals() throws Exception {
        JsonNode page0 = search("?q=java&size=12&page=0");
        JsonNode page1 = search("?q=java&size=12&page=1");
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content").size()).isEqualTo(12);
        assertThat(page1.get("content").size()).isEqualTo(11);
        Set<Long> ids = new HashSet<>();
        page0.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        page1.get("content").forEach(n -> ids.add(n.get("id").asLong()));
        assertThat(ids).hasSize(23);
    }

    @Test
    void zeroResults_isbnSearch_prefixSearch_availableFilter() throws Exception {
        assertThat(search("?q=xyzzy").get("totalElements").asLong()).isZero();
        String isbn = CatalogueFixture.isbn13(b.getId().intValue() * 100 + 1);
        String hyphenated = isbn.substring(0, 3) + "-" + isbn.substring(3, 8) + "-" + isbn.substring(8);
        JsonNode byIsbn = search("?q=" + hyphenated);
        assertThat(byIsbn.get("totalElements").asLong()).isEqualTo(1);
        assertThat(byIsbn.get("content").get(0).get("title").asString()).isEqualTo("Java Programming B1");
        assertThat(search("?q=progr&size=48").get("totalElements").asLong()).isEqualTo(23);
        assertThat(search("?q=author").get("totalElements").asLong()).isEqualTo(23);
        assertThat(json(mvc.perform(get("/api/public/catalogue?q={q}", "author c3")).andReturn()).get("totalElements").asLong()).isEqualTo(1);
        // i % 3 == 0 got zero copies: A3,A6,A9,B3,B6,C3 -> 6 unavailable titles
        assertThat(search("?q=java&available=true&size=48").get("totalElements").asLong()).isEqualTo(17);
    }

    @Test
    void sameTitleInSeveralLibrariesKeepsAvailabilitySeparate() throws Exception {
        Map<String, Object> book = Map.of("title", "Clean Code", "authors", List.of("Robert C. Martin"));
        mvc.perform(postAuthed("/api/manage/libraries/" + a.getId() + "/holdings", token(a),
                Map.of("isbn", "978-0-13-235088-4", "book", book, "copies", 2))).andExpect(status().isCreated());
        long bookId = jdbc.queryForObject("SELECT id FROM books WHERE isbn13 = '9780132350884'", Long.class);
        // Library B attaches the SAME bibliographic record by ISBN, with zero available copies
        mvc.perform(postAuthed("/api/manage/libraries/" + b.getId() + "/holdings", token(b), Map.of("isbn", "9780132350884", "copies", 0)))
                .andExpect(status().isCreated());
        mvc.perform(postAuthed("/api/manage/libraries/" + c.getId() + "/holdings", token(c), Map.of("bookId", bookId, "copies", 1)))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE isbn13 = '9780132350884'", Long.class)).isEqualTo(1);

        JsonNode details = json(mvc.perform(get("/api/public/catalogue/" + bookId)).andExpect(status().isOk()).andReturn());
        Map<String, Integer> availability = new java.util.HashMap<>();
        details.get("holdings").forEach(h -> availability.put(h.get("libraryName").asString(), h.get("availableCopies").asInt()));
        assertThat(availability).containsEntry("Library A", 2).containsEntry("Library B", 0).containsEntry("Library C", 1);
        assertThat(details.get("totalAvailable").asInt()).isEqualTo(3);
        assertThat(details.get("isbnCoverUrl").asString()).contains("9780132350884");
        // Library B is the only... not: book is shared, so B's librarian cannot edit the shared record
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/manage/libraries/" + b.getId() + "/books/" + bookId)
                        .header("Authorization", "Bearer " + token(b)).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("title", "Hacked", "authors", List.of("X")))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("BOOK_SHARED"));
    }

    @Test
    void publicCatalogueNeverExposesPrivateFields() throws Exception {
        String body = mvc.perform(get("/api/public/catalogue?q=java")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("accessionNo").doesNotContain("memberId").doesNotContain("email");
        mvc.perform(get("/api/public/catalogue/999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
        JsonNode cats = json(mvc.perform(get("/api/public/categories")).andReturn());
        assertThat(cats.size()).isEqualTo(8);
    }
}
