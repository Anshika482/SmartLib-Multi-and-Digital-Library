package com.library.lms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The public boundary, over HTTP, with no token at all.
 *
 * <p>Two halves. That the public catalogue really is reachable signed-out -
 * otherwise the visitor experience silently falls back to nothing - and that
 * opening it moved nothing else out from behind the gate.</p>
 *
 * <p>The property set is copied exactly from {@code SecurityHttpIntegrationTest}
 * so Spring reuses that context instead of building a second one against its
 * own MySQL schema. The schema named here is a throwaway, never the developer's
 * {@code library_db}.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class PublicCatalogueApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---------- the public half ----------

    @Test
    void theCatalogueIsReadableWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/api/public/catalogue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists());
    }

    @Test
    void theLibraryListIsReadableWithoutSigningIn() throws Exception {
        mockMvc.perform(get("/api/public/libraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void aSearchTermIsAccepted() throws Exception {
        mockMvc.perform(get("/api/public/catalogue").param("keyword", "dune"))
                .andExpect(status().isOk());
    }

    @Test
    void anOversizedPageIsRefusedRatherThanServed() throws Exception {
        mockMvc.perform(get("/api/public/catalogue").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Whatever the catalogue holds, no row may carry an operational field. The
     * matcher runs against every element, so an empty catalogue passes
     * vacuously and a populated one is checked properly.
     */
    @ParameterizedTest
    @ValueSource(strings = {"id", "availableCopies", "totalCopies", "libraryId", "categoryId", "version"})
    void noPublicRowCarriesAnOperationalField(String forbidden) throws Exception {
        mockMvc.perform(get("/api/public/catalogue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*]." + forbidden).isEmpty());
    }

    // ---------- the gate is where it was ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/books",
            "/api/categories",
            "/api/users/me",
            "/api/users",
            "/api/libraries",
            "/api/transactions",
            "/api/digital-resources",
            "/api/audit-events"
    })
    void everyOtherReadStillNeedsAToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void thePublicPrefixDoesNotOpenWrites() throws Exception {
        // The rule names GET and two exact paths, so anything else under the
        // prefix falls through to anyRequest().authenticated().
        mockMvc.perform(post("/api/public/catalogue")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/public/libraries")).andExpect(status().isUnauthorized());
    }

    @Test
    void thePublicPrefixIsNotAWildcard() throws Exception {
        mockMvc.perform(get("/api/public/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/public/catalogue/1")).andExpect(status().isUnauthorized());
    }
}
