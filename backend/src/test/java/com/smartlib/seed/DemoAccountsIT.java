package com.smartlib.seed;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.smartlib.support.IntegrationTest;
import com.smartlib.support.TestData;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.UserRole;

/** Demo login switched ON (the default test configuration keeps it off). */
@TestPropertySource(properties = {"smartlib.seed.demo-login=true", "smartlib.seed.demo-password=" + TestData.PASSWORD})
class DemoAccountsIT extends IntegrationTest {

    @Test
    void listsOnlyExistingUsableDemoAccounts_andTheyCanSignIn() throws Exception {
        long lib = data.library("CEN", "Central Library").getId();
        data.member("member@smartlib.demo");
        data.staff("admin.central@smartlib.demo", UserRole.ADMIN, lib, ApprovalStatus.APPROVED);
        data.superAdmin("superadmin@smartlib.demo");
        data.user("librarian.central@smartlib.demo", UserRole.LIBRARIAN, false); // unverified: not offered

        mvc.perform(get("/api/public/demo-accounts"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.password").value(TestData.PASSWORD))
                .andExpect(jsonPath("$.accounts[*].role").value(contains("MEMBER", "ADMIN")));

        mvc.perform(postJson("/api/auth/login", Map.of("email", "admin.central@smartlib.demo", "password", TestData.PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void revealsNothingWhenNoDemoAccountsExist() throws Exception {
        mvc.perform(get("/api/public/demo-accounts"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.accounts").isEmpty());
    }
}
