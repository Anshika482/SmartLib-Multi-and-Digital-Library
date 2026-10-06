package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Registration over HTTP, against a real schema.
 *
 * <p><b>This class skips itself unless the schema has had V9 applied.</b>
 * Registration writes an audit row whose action is one of the values V9 adds,
 * and MySQL will not store a value its ENUM does not list - Hibernate's
 * {@code ddl-auto=update} adds columns but never widens an ENUM. A schema built
 * before V9 would fail these tests with "Data truncated for column 'action'",
 * which says nothing about the code.
 *
 * <p>The check below reads the column definition and assumes on it, so this
 * class turns itself on the moment the widening is applied - by Flyway on a
 * fresh or production schema, or by hand on a development one - and needs no
 * edit to do so.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",

        // This class registers several times, and MockMvc presents one fixed
        // remote address - so every request here draws on the same bucket. At
        // the shipped limit the limiter would answer 429 partway through and
        // this class would be reporting on the limiter rather than on
        // registration. RegistrationRateLimiterTest covers the limiter itself.
        "registration.rate-limit.requests=500",

        // A distinct property set means a context of its own, so its pool is
        // kept small: MySQL has a fixed connection ceiling and the suite builds
        // many contexts.
        "spring.datasource.hikari.maximum-pool-size=2"
})
@AutoConfigureMockMvc
// Its own property set means its own context and its own pool. Released when
// the class finishes, so those connections are not held for the rest of the
// suite - MySQL has a fixed ceiling and this suite builds many contexts. The
// same remedy FinePaymentGatewayIntegrationTest and
// PasswordResetMailIntegrationTest already use.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    /** Whether this schema can store what registration writes. */
    private boolean schemaHasV9() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COLUMN_TYPE FROM information_schema.columns"
                                + " WHERE table_schema = DATABASE() AND table_name = 'audit_events'"
                                + " AND column_name = 'action'")) {
            return rows.next() && rows.getString(1).contains("USER_REGISTERED");
        }
    }

    @BeforeEach
    void requireV9() throws Exception {
        Assumptions.assumeTrue(schemaHasV9(),
                "Schema predates V9: audit_events.action cannot store USER_REGISTERED yet."
                        + " Apply V9, or the ALTER it contains, to this schema.");
    }

    private String body(String type, String username, Long libraryId, String libraryName) {
        StringBuilder json = new StringBuilder("{")
                .append("\"type\":\"").append(type).append("\",")
                .append("\"username\":\"").append(username).append("\",")
                .append("\"email\":\"").append(username).append("@example.invalid\",")
                .append("\"fullName\":\"A Person\",")
                .append("\"password\":\"a-long-enough-password\"");

        if (libraryId != null) {
            json.append(",\"libraryId\":").append(libraryId);
        }
        if (libraryName != null) {
            json.append(",\"libraryName\":\"").append(libraryName).append("\"");
        }

        return json.append("}").toString();
    }

    private Long anyJoinableLibraryId() throws Exception {
        String listing = mockMvc.perform(get("/api/public/libraries"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The listing is the one a visitor sees, so this also proves the id a
        // registrant needs is published there.
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(listing);
        Assumptions.assumeTrue(id.find(), "No joinable library on this schema to register into.");
        return Long.valueOf(id.group(1));
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    // ---------- the public endpoint ----------

    @Test
    void registeringIsPublicAndNeedsNoToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MEMBER", unique("member"), anyJoinableLibraryId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void aLibrarianApplicationIsRecordedAsPending() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LIBRARIAN", unique("wants-to-work"), anyJoinableLibraryId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void anAdministratorApplicationOpensNothingUntilItIsApproved() throws Exception {
        String library = unique("New Library");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ADMIN", unique("wants-a-library"), null, library)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        String listing = mockMvc.perform(get("/api/public/libraries"))
                .andReturn().getResponse().getContentAsString();

        assertThat(listing)
                .as("a library nobody approved must not be offered to members")
                .doesNotContain(library);
    }

    // ---------- no response ever carries a credential ----------

    @Test
    void theResponseCarriesNoTokenAndNoAccountDetail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MEMBER", unique("quiet"), anyJoinableLibraryId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.role").doesNotExist());
    }

    // ---------- a request cannot choose its own authority ----------

    @ParameterizedTest
    @ValueSource(strings = {"SUPER_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_ADMIN", "root"})
    void aRequestNamingAPrivilegedTypeIsRejectedOutright(String type) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(type, unique("climber"), anyJoinableLibraryId(), null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void smugglingARoleFieldIntoTheBodyChangesNothing() throws Exception {
        String username = unique("smuggler");
        Long libraryId = anyJoinableLibraryId();

        // A role field the request object does not have. It is ignored, and the
        // account is a member because MEMBER is what the type said.
        String smuggled = "{\"type\":\"MEMBER\",\"role\":\"ROLE_SUPER_ADMIN\","
                + "\"username\":\"" + username + "\","
                + "\"email\":\"" + username + "@example.invalid\","
                + "\"fullName\":\"A Person\",\"password\":\"a-long-enough-password\","
                + "\"libraryId\":" + libraryId + "}";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(smuggled))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    // ---------- validation ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"MEMBER\"}",
            "{\"username\":\"x\",\"email\":\"x@example.invalid\",\"fullName\":\"X\",\"password\":\"12345678\"}",
            "{\"type\":\"MEMBER\",\"username\":\"ab\",\"email\":\"not-an-email\",\"fullName\":\"X\","
                    + "\"password\":\"short\"}"
    })
    void anIncompleteRegistrationIsRejected(String json) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void joiningALibraryThatIsNotThereIsRefusedWithoutSayingWhy() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MEMBER", unique("lost"), 999_999L, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("That library is not available to join."));
    }

    // ---------- the decision endpoints are not public ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/registrations/pending"
    })
    void listingPendingRegistrationsNeedsAToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void decidingNeedsAToken() throws Exception {
        mockMvc.perform(post("/api/registrations/1/approve")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/registrations/1/reject")).andExpect(status().isUnauthorized());
    }

    // ---------- opening registration opened nothing else ----------

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
    void everyProtectedEndpointStillNeedsAToken(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
