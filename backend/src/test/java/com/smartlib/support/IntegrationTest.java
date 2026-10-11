package com.smartlib.support;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.smartlib.common.time.AppClock;
import com.smartlib.security.PrincipalService;
import com.smartlib.security.RateLimiter;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Base class for full-stack tests against a real MySQL test schema. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class IntegrationTest {

    /** Shared stand-in for Gemini and Razorpay (one per JVM, so all tests share one Spring context). */
    protected static final StubServer stub = StubServer.start();

    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("smartlib.gemini.base-url", stub::baseUrl);
        registry.add("smartlib.razorpay.base-url", stub::baseUrl);
    }

    private static final List<String> KEEP = List.of("flyway_schema_history", "categories", "platform_settings");

    @Autowired
    protected WebApplicationContext context;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected CapturingMailTransport mail;
    @Autowired
    protected AppClock clock;
    @Autowired
    protected RateLimiter rateLimiter;
    @Autowired
    protected PrincipalService principals;
    @Autowired
    protected TestData data;

    protected MockMvc mvc;

    @BeforeEach
    void resetState() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'",
                String.class);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : tables) {
            if (!KEEP.contains(t.toLowerCase())) {
                jdbc.execute("TRUNCATE TABLE `" + t + "`");
            }
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("UPDATE platform_settings SET setting_value = 'true' WHERE setting_key IN ('ai.enabled','registration.librarian.open')");
        mail.clear();
        clock.reset();
        rateLimiter.reset();
        rateLimiter.setEnabled(true);
        principals.evictAll();
    }

    // ---------- HTTP helpers ----------

    protected JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    protected MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder getAuthed(String url, String token) {
        return get(url).header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder postAuthed(String url, String token, Object body) {
        return postJson(url, body).header("Authorization", "Bearer " + token);
    }

    protected static Cookie refreshCookie(MvcResult result) {
        Cookie c = result.getResponse().getCookie("smartlib_rt");
        if (c == null) {
            throw new AssertionError("No refresh cookie set");
        }
        return c;
    }
}
