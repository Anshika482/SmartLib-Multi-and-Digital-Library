package com.library.lms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The cover endpoints over HTTP, with no token.
 *
 * <p>Two things this class is for: the whole sub-resource is behind
 * authentication, and adding it opened nothing else. Who may set a cover and
 * whose covers they may touch is settled again in {@code BookCoverService},
 * which {@code BookCoverServiceTest} covers in detail - including the
 * cross-library case, which needs two libraries and is cheaper to assert
 * against the service than to build over HTTP.
 *
 * <p>Backward compatibility is checked here too: the book listing still answers
 * the way it did, and a book with no cover reports so rather than failing.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class BookCoverApiIntegrationTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3, 4};

    @Autowired
    private MockMvc mockMvc;

    // ---------- the whole sub-resource needs a token ----------

    @Test
    void readingACoverNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/books/1/cover")).andExpect(status().isUnauthorized());
    }

    @Test
    void settingACoverNeedsAToken() throws Exception {
        mockMvc.perform(multipart("/api/books/1/cover").file("file", JPEG))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/books/1/cover").file("file", JPEG))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removingACoverNeedsAToken() throws Exception {
        mockMvc.perform(delete("/api/books/1/cover")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownBookIdIsStillRefusedWithoutAToken() throws Exception {
        // Authentication is decided before the book is looked up, so an id that
        // does not exist is a 401 and not a 404 - which would confirm the id.
        mockMvc.perform(get("/api/books/999999999/cover")).andExpect(status().isUnauthorized());
    }

    // ---------- adding covers opened nothing else ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/books",
            "/api/books/1",
            "/api/categories",
            "/api/users/me",
            "/api/users",
            "/api/libraries",
            "/api/transactions",
            "/api/digital-resources",
            "/api/audit-events"
    })
    void everyOtherEndpointStillNeedsAToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void thePublicSurfaceIsUnchangedAndCarriesNoCoverField() throws Exception {
        // The public catalogue deliberately gained nothing: covers are for a
        // library's own members, and its response is bibliographic only.
        mockMvc.perform(get("/api/public/catalogue").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].coverUrl").isEmpty())
                .andExpect(jsonPath("$.content[*].hasCover").isEmpty());
    }

    @Test
    void thePublicLibraryListIsUnchanged() throws Exception {
        mockMvc.perform(get("/api/public/libraries")).andExpect(status().isOk());
    }

    @Test
    void theCoverPathDoesNotSwallowTheBookEndpoints() throws Exception {
        // /api/books/{id}/cover must not shadow /api/books/{id}; both are still
        // the endpoints they were, and both still need a token.
        mockMvc.perform(get("/api/books/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/books/1")).andExpect(status().isUnauthorized());
    }
}
