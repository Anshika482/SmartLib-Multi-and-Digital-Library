package com.smartlib.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartlib.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

class DemoDataSeederIT extends IntegrationTest {

    @Autowired
    DemoDataSeeder seeder;
    @Autowired
    TransactionTemplate tx;

    @Test
    void seedsConsistentDemoData() throws Exception {
        tx.executeWithoutResult(s -> seeder.seed("Demo@pass2026"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM libraries", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books", Long.class)).isEqualTo(63);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email LIKE '%@smartlib.demo'", Long.class)).isEqualTo(16);
        // Demo login is off in this configuration: even with demo accounts present, the login page gets nothing
        JsonNode demo = json(mvc.perform(get("/api/public/demo-accounts")).andReturn());
        assertThat(demo.get("enabled").asBoolean()).isFalse();
        assertThat(demo.get("accounts").size()).isZero();
        // copy statuses agree with loans and reservations
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_copies WHERE status = 'BORROWED'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM borrowings WHERE status = 'ACTIVE'", Long.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_copies WHERE status = 'RESERVED'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests WHERE status = 'APPROVED'", Long.class));

        long cleanCode = jdbc.queryForObject("SELECT id FROM books WHERE isbn13 = '9780132350884'", Long.class);
        JsonNode details = json(mvc.perform(get("/api/public/catalogue/" + cleanCode)).andReturn());
        Map<String, Integer> availability = new HashMap<>();
        details.get("holdings").forEach(h -> availability.put(h.get("libraryName").asString(), h.get("availableCopies").asInt()));
        assertThat(availability).containsEntry("Central Library", 1).containsEntry("West End Library", 0).containsEntry("Riverside Library", 1);

        assertThat(json(mvc.perform(get("/api/public/catalogue?q=java&size=48")).andReturn()).get("totalElements").asLong()).isGreaterThanOrEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fines WHERE status = 'UNPAID'", Long.class)).isEqualTo(2);
    }
}
