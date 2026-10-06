package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * That no endpoint is reachable without a token unless it is meant to be.
 *
 * <p><b>Every other security test names the endpoints it checks.</b> That is
 * fine for the endpoints that existed when the test was written, and no help at
 * all for the one somebody adds next month and forgets to write a rule for -
 * which is the realistic way an API springs a leak. This class asks the
 * application what it actually serves and checks all of it, so a new endpoint
 * without a rule fails the build rather than shipping open.
 *
 * <p><b>The public list is exact.</b> It is asserted in both directions: every
 * endpoint on it must exist, and every endpoint not on it must refuse an
 * anonymous caller. Making something public therefore means editing this list,
 * which is a line in a diff somebody reviews.
 *
 * <p>Scoped to {@code /api}. The actuator is served by a different handler
 * mapping and has {@link ActuatorHealthIntegrationTest} of its own; {@code /error}
 * is the container's own forward and carries no data of its own.
 *
 * <p>No request here is authenticated, so none reaches a controller: the filter
 * chain answers first. Nothing is written, and the path variables below are
 * filled with a harmless {@code 1} that no handler ever sees.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class EndpointAuthorizationCoverageIntegrationTest {

    /**
     * Exactly the endpoints that may answer somebody with no token.
     *
     * <p>Each one is deliberate and each has its own test elsewhere for what it
     * does and does not reveal:
     *
     * <ul>
     *   <li>the six auth paths, which is how a caller gets a token in the first
     *       place, or gets back in having lost one;</li>
     *   <li>registration, which a prospective member has no token for;</li>
     *   <li>the public catalogue, which carries bibliographic facts and no ids,
     *       copy counts, resources or member data;</li>
     *   <li>chat, which a visitor may use and which is rate limited.</li>
     * </ul>
     */
    private static final Set<String> PUBLIC_BY_DESIGN = Set.of(
            "POST /api/auth/login",
            "POST /api/auth/refresh",
            "POST /api/auth/logout",
            "POST /api/auth/forgot-password",
            "POST /api/auth/reset-password",
            "POST /api/auth/register",
            "GET /api/public/catalogue",
            "GET /api/public/libraries",
            "POST /api/chat");

    @Autowired
    private MockMvc mockMvc;

    /**
     * The application's own controller mappings.
     *
     * <p>Named, because the actuator contributes a second bean of this type. That
     * one is the actuator's and is checked by
     * {@link ActuatorHealthIntegrationTest}; taking it here would mix two
     * different security arrangements into one sweep.</p>
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** Every {@code /api} endpoint the application serves, as "METHOD /path". */
    private List<String> apiEndpoints() {
        List<String> endpoints = new ArrayList<>();

        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith("/api")) {
                    continue;
                }

                Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                        info.getMethodsCondition().getMethods();

                if (methods.isEmpty()) {
                    // A mapping with no method answers all of them. Checked as a
                    // GET, which is enough to prove the chain refuses it.
                    endpoints.add("GET " + pattern);
                } else {
                    methods.forEach(method -> endpoints.add(method.name() + " " + pattern));
                }
            }
        });

        return endpoints;
    }

    /** The patterns of one mapping, whichever pattern parser this application uses. */
    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            Set<String> patterns = new TreeSet<>();
            info.getPathPatternsCondition().getPatterns()
                    .forEach(pattern -> patterns.add(pattern.getPatternString()));
            return patterns;
        }

        return new TreeSet<>(info.getPatternValues());
    }

    /** A pattern with its variables filled in, so it can actually be requested. */
    private static String concrete(String pattern) {
        return pattern.replaceAll("\\{[^}]+}", "1");
    }

    private MvcResult anonymously(String method, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(HttpMethod.valueOf(method), concrete(path))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    // ---------- 1. nothing private answers without a token ----------

    @Test
    void everyEndpointEitherIsPublicByDesignOrRefusesAnAnonymousCaller() throws Exception {
        List<String> reachable = new ArrayList<>();

        for (String endpoint : apiEndpoints()) {
            if (PUBLIC_BY_DESIGN.contains(endpoint)) {
                continue;
            }

            int status = anonymously(endpoint.split(" ")[0], endpoint.split(" ")[1])
                    .getResponse().getStatus();

            // 401 is the expected answer. 403 is acceptable too - some rules
            // refuse before identifying anybody. Anything else means the filter
            // chain let the request through to a handler, which for an
            // unauthenticated caller is the leak this test exists to find.
            if (status != 401 && status != 403) {
                reachable.add(endpoint + " -> " + status);
            }
        }

        assertThat(reachable)
                .as("every endpoint not on the public list must refuse an anonymous caller")
                .isEmpty();
    }

    // ---------- 2. the public list is exactly right ----------

    @Test
    void everyEndpointOnThePublicListStillExists() {
        List<String> served = apiEndpoints();

        // A stale entry would quietly widen nothing, but it would misdescribe the
        // application - and the next person would trust it.
        assertThat(served)
                .as("the public list must not name endpoints this application no longer serves")
                .containsAll(PUBLIC_BY_DESIGN);
    }

    @Test
    void everyEndpointOnThePublicListReallyIsReachableWithoutAToken() throws Exception {
        for (String endpoint : PUBLIC_BY_DESIGN) {
            int status = anonymously(endpoint.split(" ")[0], endpoint.split(" ")[1])
                    .getResponse().getStatus();

            // Whatever these answer - 200, 400 for a body they did not like, 429
            // when rate limited - it must not be the filter chain turning them
            // away. If one of these starts answering 401, a public feature has
            // silently closed.
            assertThat(status).as("%s is meant to be public", endpoint).isNotEqualTo(401);
        }
    }

    @Test
    void theApplicationServesWhatThisTestThinksItServes() {
        // A guard on the guard: if the enumeration ever returns nothing - a
        // renamed mapping bean, a changed pattern parser - every assertion above
        // would pass vacuously.
        assertThat(apiEndpoints())
                .as("the endpoint enumeration must find the application's own endpoints")
                .hasSizeGreaterThan(40)
                .contains("GET /api/dashboard", "POST /api/transactions/issue", "GET /api/reports");
    }

    // ---------- 3. the paths that must never be public ----------

    @Test
    void theEndpointsThatWouldMatterMostAreAmongTheRefused() throws Exception {
        // Named explicitly as well as swept above, so the sweep failing open
        // cannot hide these particular ones.
        List<String> mustRefuse = List.of(
                "POST /api/auth/password",
                "GET /api/users",
                "GET /api/users/me",
                "PATCH /api/users/1/status",
                "GET /api/audit-events",
                "GET /api/reports",
                "GET /api/dashboard",
                "POST /api/libraries",
                "GET /api/transactions/fines",
                "POST /api/transactions/issue",
                "POST /api/transactions/1/payment-order",
                "POST /api/transactions/1/payment-verification",
                "GET /api/registrations/pending",
                "POST /api/books",
                "POST /api/books/1/cover",
                "GET /api/books/1/cover",
                "GET /api/digital-resources",
                "GET /api/borrow-requests",
                "POST /api/borrow-requests");

        for (String endpoint : mustRefuse) {
            int status = anonymously(endpoint.split(" ")[0], endpoint.split(" ")[1])
                    .getResponse().getStatus();

            assertThat(status).as("%s must not answer an anonymous caller", endpoint).isIn(401, 403);
        }
    }

    @Test
    void changingOwnPasswordIsNotOneOfThePublicAuthPaths() throws Exception {
        // The auth exemptions are each an exact path and an exact method, so this
        // one falls through to the catch-all. A broad /api/auth/** permitAll -
        // an easy shortcut to write - would hand password changes to anybody.
        assertThat(anonymously("POST", "/api/auth/password").getResponse().getStatus())
                .isIn(401, 403);

        assertThat(PUBLIC_BY_DESIGN).noneSatisfy(entry ->
                assertThat(entry).contains("/api/auth/password"));
    }
}
