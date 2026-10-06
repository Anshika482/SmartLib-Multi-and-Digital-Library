package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The headers every answer carries, whether or not anybody asked for them.
 *
 * <p>Spring Security sets these by default, which is exactly why they are worth
 * asserting: nothing in this application asks for them, so nothing would notice
 * if a later change to {@code SecurityConfig} - a {@code .headers(...)} block
 * written to add one thing - turned the rest off. A default nobody tests is a
 * default nobody keeps.
 *
 * <p>What is checked, and why each matters for a JSON API:
 *
 * <ul>
 *   <li><b>nosniff</b> - stops a browser deciding for itself that a JSON
 *       response is really HTML or a script, which is how an API response
 *       becomes an execution context.</li>
 *   <li><b>X-Frame-Options</b> - nothing here should be framed. It matters
 *       because an error page or a future HTML response would otherwise be
 *       embeddable.</li>
 *   <li><b>no-store on authenticated answers</b> - a member's loans and fines
 *       must not sit in a shared cache or in the back button after they sign
 *       out.</li>
 * </ul>
 *
 * <p>Deliberately not asserted: HSTS, which Spring emits only over HTTPS and
 * which MockMvc therefore cannot see, and Content-Security-Policy, which is not
 * configured - it is a page-level control and this application serves JSON,
 * while the frontend is served and secured separately.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class SecurityHeaderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** An answer to somebody with no token. Refused, and still carrying its headers. */
    private MvcResult refused() throws Exception {
        return mockMvc.perform(get("/api/dashboard")).andReturn();
    }

    /** A public answer, which is the one most likely to be cached by something. */
    private MvcResult publicAnswer() throws Exception {
        return mockMvc.perform(get("/api/public/libraries")).andReturn();
    }

    @Test
    void everyAnswerForbidsContentTypeSniffing() throws Exception {
        assertThat(refused().getResponse().getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(publicAnswer().getResponse().getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
    }

    @Test
    void everyAnswerRefusesToBeFramed() throws Exception {
        assertThat(refused().getResponse().getHeader("X-Frame-Options"))
                .isEqualTo("DENY");
    }

    @Test
    void anAnswerToAnUnauthenticatedCallerIsNotCached() throws Exception {
        String cacheControl = refused().getResponse().getHeader("Cache-Control");

        // A 401 body is harmless, but the header is set for every answer and this
        // is where it is cheapest to notice it disappearing.
        assertThat(cacheControl).isNotNull();
        assertThat(cacheControl).contains("no-store");
    }

    @Test
    void theLoginEndpointDoesNotInviteCaching() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andReturn();

        // Whatever it answers, a token exchange must not be storable.
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    void noAnswerNamesTheServerOrItsVersion() throws Exception {
        // A version number is free reconnaissance. Spring Boot does not send one
        // by default and this is what would notice if that changed.
        assertThat(refused().getResponse().getHeader("Server")).isNull();
        assertThat(refused().getResponse().getHeader("X-Powered-By")).isNull();
    }

    @Test
    void aCoverImageIsServedWithSniffingOffToo() throws Exception {
        // Covers are the one binary this API serves, and the one response a
        // browser is most inclined to guess the type of. Refused here for want of
        // a token, which is enough to see the header.
        MvcResult result = mockMvc.perform(get("/api/books/1/cover")).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
