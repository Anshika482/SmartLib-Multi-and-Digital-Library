package com.smartlib.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.smartlib.support.IntegrationTest;
import com.smartlib.user.User;

/** BOOTSTRAP_SUPER_ADMIN_* configured: that account is the only Super Admin; every other one stays disabled. */
@TestPropertySource(properties = {"smartlib.bootstrap.super-admin-email=Owner@Example.com",
        "smartlib.bootstrap.super-admin-password=Tulip-Desk-2026", "smartlib.bootstrap.super-admin-name=Platform Owner"})
class SingleSuperAdminIT extends IntegrationTest {

    @Autowired
    private BootstrapRunner bootstrap;

    private String accountStatus(String email) {
        return jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
    }

    private String loginToken(String email, String password) throws Exception {
        MvcResult r = mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("SUPER_ADMIN")).andReturn();
        return json(r).get("accessToken").asString();
    }

    @Test
    void createsTheConfiguredSuperAdminAndDisablesEveryOtherOne() throws Exception {
        User demoBoss = data.superAdmin("superadmin@smartlib.demo");
        String oldToken = data.token(demoBoss);

        bootstrap.run(null);

        assertThat(accountStatus("owner@example.com")).isEqualTo("ACTIVE");
        assertThat(accountStatus("superadmin@smartlib.demo")).isEqualTo("DISABLED");
        String ownerToken = loginToken("owner@example.com", "Tulip-Desk-2026");
        // the other Super Admin can no longer sign in, and its existing session is revoked
        mvc.perform(postJson("/api/auth/login", Map.of("email", "superadmin@smartlib.demo", "password", com.smartlib.support.TestData.PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(getAuthed("/api/platform/libraries", oldToken)).andExpect(status().isUnauthorized());
        // ...and cannot be re-activated from the Users page
        mvc.perform(authed(patch("/api/platform/users/" + demoBoss.getId() + "/status"), ownerToken).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("status", "ACTIVE")))).andExpect(status().isConflict());
        assertThat(accountStatus("superadmin@smartlib.demo")).isEqualTo("DISABLED");

        bootstrap.run(null); // idempotent on every start
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN' AND status = 'ACTIVE'", Long.class)).isEqualTo(1);
    }

    @Test
    void convertsAnExistingAccountWithThatEmailAndUsesTheConfiguredPassword() throws Exception {
        User member = data.member("owner@example.com");
        jdbc.update("INSERT INTO staff_assignments (user_id, library_id, approval_status, created_at, updated_at) VALUES (?, ?, 'PENDING', NOW(6), NOW(6))",
                member.getId(), data.library("CEN", "Central Library").getId());

        bootstrap.run(null);

        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = 'owner@example.com'", String.class)).isEqualTo("SUPER_ADMIN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_assignments WHERE user_id = ?", Long.class, member.getId())).isZero();
        mvc.perform(postJson("/api/auth/login", Map.of("email", "owner@example.com", "password", com.smartlib.support.TestData.PASSWORD)))
                .andExpect(status().isUnauthorized());
        loginToken("owner@example.com", "Tulip-Desk-2026");
    }

    @Test
    void superAdminApplicationsCannotBeApproved() throws Exception {
        bootstrap.run(null);
        String ownerToken = loginToken("owner@example.com", "Tulip-Desk-2026");
        User applicant = data.user("applicant@example.com", com.smartlib.user.UserRole.SUPER_ADMIN, true);
        jdbc.update("INSERT INTO staff_assignments (user_id, library_id, approval_status, origin, designation, created_at, updated_at) VALUES (?, NULL, 'PENDING', 'APPLICATION', 'IT', NOW(6), NOW(6))",
                applicant.getId());
        long id = jdbc.queryForObject("SELECT id FROM staff_assignments WHERE user_id = ?", Long.class, applicant.getId());
        mvc.perform(postAuthed("/api/platform/applications/" + id + "/approve", ownerToken, Map.of())).andExpect(status().isConflict());
    }
}
