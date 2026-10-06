package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The CORS configuration this repository actually ships with.
 *
 * <p>{@code CorsIntegrationTest} covers the mechanism thoroughly, but it sets
 * {@code security.cors.allowed-origins} itself. That is why a broken default
 * could ship: every CORS test passed while the configuration a developer
 * actually runs allowed nothing, and Spring refused any request carrying an
 * Origin header before authentication - which is every browser POST, including
 * a same-origin one. Sign-in and the assistant failed in a browser while GETs
 * appeared to work, because browsers omit Origin on a same-origin GET.</p>
 *
 * <p><b>This class overrides no CORS property on purpose.</b> It asserts the
 * default from {@code application.properties}, so the thing that was wrong is
 * the thing under test.</p>
 *
 * <p>The datasource properties are copied from the other integration tests so
 * Spring reuses their context rather than building another.</p>
 *
 * <p>Because that context is shared, the public chat limiter is shared too, and
 * MockMvc presents one fixed remote address - so an allowed chat POST here may
 * legitimately be answered 429 depending on what ran first. The assertion is
 * therefore about the CORS outcome rather than the status: a refusal by CORS is
 * a 403 with no allow-origin header, so anything else with the header present
 * means the request got through. The limiter is covered by
 * {@code PublicChatRateLimiterTest}, and chat behaviour by
 * {@code AnonymousChatApiIntegrationTest}.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class DefaultCorsIntegrationTest {

    private static final String DEV_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    // ---------- the browser origin the dev setup uses is accepted ----------

    @Test
    void thePublicAssistantAcceptsABrowserPostFromTheDevOrigin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(header().string("Access-Control-Allow-Origin", DEV_ORIGIN))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("403 would mean the CORS filter refused it before the controller ran")
                .isNotEqualTo(403);
    }

    /**
     * Sign-in from the dev origin reaches the authentication logic.
     *
     * <p>401 is the right answer for credentials that do not exist. What matters
     * is that it is a 401 and not the 403 the CORS filter returns before
     * authentication ever runs - and that the response carries the allow-origin
     * header, so the browser lets the client read it.</p>
     */
    @Test
    void signInFromTheDevOriginReachesAuthenticationRatherThanTheCorsFilter() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever-it-is\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", DEV_ORIGIN));
    }

    @Test
    void aPreflightFromTheDevOriginIsAnswered() throws Exception {
        mockMvc.perform(options("/api/chat")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEV_ORIGIN));
    }

    @Test
    void aGetFromTheDevOriginIsAllowedToo() throws Exception {
        mockMvc.perform(get("/api/public/catalogue").header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DEV_ORIGIN));
    }

    // ---------- and nothing else is ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example",
            "https://localhost:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5173"
    })
    void anyOtherOriginIsStillRefused(String origin) throws Exception {
        // Including near-misses: a different scheme, a different port and the
        // loopback address by number are all different origins.
        mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.ORIGIN, origin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- opening CORS did not open anything else ----------

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
    void protectedEndpointsStillRequireATokenEvenFromTheAllowedOrigin(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAllowedOriginDoesNotMakeAWriteEndpointPublic() throws Exception {
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void credentialsAreNotAllowedForTheDevOriginEither() throws Exception {
        // The token travels in the Authorization header, which the client sets.
        // Credentialed CORS would additionally hand a listed origin cookies.
        mockMvc.perform(options("/api/chat")
                        .header(HttpHeaders.ORIGIN, DEV_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void aRequestWithNoOriginIsUnaffected() throws Exception {
        mockMvc.perform(get("/api/public/catalogue"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
