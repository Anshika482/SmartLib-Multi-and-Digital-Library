package com.smartlib.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.smartlib.library.Library;
import com.smartlib.support.IntegrationTest;
import com.smartlib.support.TestData;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;

/** ADMIN and SUPER_ADMIN self-registration: email verification AND approval by an existing SUPER_ADMIN, never instant access. */
class AdminApplicationIT extends IntegrationTest {

    @Autowired
    private UserRepository users;
    @Autowired
    private PlatformSettings settings;

    /** Settings survive the per-test table cleanup, so re-open applications before every test. */
    @BeforeEach
    void openApplications() {
        settings.set(PlatformSettings.ADMIN_REGISTRATION_OPEN, "true", null);
    }

    private static Map<String, Object> adminForm(String email, Long libraryId) {
        Map<String, Object> form = new HashMap<>(Map.of("fullName", "Asha Admin", "email", email, "phone", "+91 98765 43210",
                "password", TestData.PASSWORD, "confirmPassword", TestData.PASSWORD, "designation", "Head Librarian",
                "employeeRef", "EMP-77", "applicationNote", "I run the Central branch and need to manage its staff and policies.",
                "confirmAuthority", true, "acceptTerms", true));
        if (libraryId != null) {
            form.put("libraryId", libraryId);
        }
        return form;
    }

    private long applicationId(String email) {
        return jdbc.queryForObject("SELECT s.id FROM staff_assignments s JOIN users u ON u.id = s.user_id WHERE u.email = ?",
                Long.class, email);
    }

    private void verify(String email, String expectedMessage) throws Exception {
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", mail.latestOtp(email))))
                .andExpect(jsonPath("$.nextStep").value("AWAIT_APPROVAL"))
                .andExpect(jsonPath("$.message").value(containsString(expectedMessage)));
    }

    @Test
    void adminApplication_needsVerificationAndSuperAdminApproval_neverTheLibraryAdmin() throws Exception {
        Library lib = data.library("CEN", "Central Library");
        Library other = data.library("WST", "West End Library");
        User boss = data.superAdmin("boss@example.com");
        User centralAdmin = data.staff("admin-central@example.com", UserRole.ADMIN, lib.getId(), ApprovalStatus.APPROVED);
        String email = "asha@example.com";

        // Essential fields are required
        Map<String, Object> incomplete = new HashMap<>(adminForm(email, lib.getId()));
        incomplete.remove("phone");
        incomplete.remove("designation");
        incomplete.put("applicationNote", "too short");
        incomplete.put("confirmAuthority", false);
        mvc.perform(postJson("/api/auth/register/admin", incomplete))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItems("phone", "designation", "applicationNote", "confirmAuthority")));
        mvc.perform(postJson("/api/auth/register/admin", adminForm(email, null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("libraryId"));

        mvc.perform(postJson("/api/auth/register/admin", adminForm(email, lib.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.nextStep").value("VERIFY_EMAIL"));
        assertThat(mail.latestTo(boss.getEmail())).hasValueSatisfying(m -> assertThat(m.subject()).contains("New admin application"));
        assertThat(mail.latestTo(centralAdmin.getEmail())).isEmpty();

        long id = applicationId(email);
        String bossToken = data.token(boss);
        mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mvc.perform(postAuthed("/api/platform/applications/" + id + "/approve", bossToken, Map.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        verify(email, "Super Admin approval");
        mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APPROVAL_PENDING"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.libraryName").value("Central Library"));

        // The library's own admin can neither approve it (library endpoint covers librarians only) nor see platform applications
        String centralToken = data.token(centralAdmin);
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + id + "/approve", centralToken, Map.of()))
                .andExpect(status().isNotFound());
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + id + "/reject", centralToken,
                Map.of("reason", "no"))).andExpect(status().isNotFound());
        mvc.perform(getAuthed("/api/platform/applications", centralToken)).andExpect(status().isForbidden());

        // Only self-registered applications are listed (the fixture admin is not)
        mvc.perform(getAuthed("/api/platform/applications?status=PENDING", bossToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].email").value(email))
                .andExpect(jsonPath("$.content[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].libraryName").value("Central Library"))
                .andExpect(jsonPath("$.content[0].designation").value("Head Librarian"))
                .andExpect(jsonPath("$.content[0].emailVerified").value(true));

        mvc.perform(postAuthed("/api/platform/applications/" + id + "/approve", bossToken, Map.of())).andExpect(status().isNoContent());
        mvc.perform(postAuthed("/api/platform/applications/" + id + "/approve", bossToken, Map.of())).andExpect(status().isConflict());
        assertThat(mail.latestTo(email)).hasValueSatisfying(m -> assertThat(m.subject()).contains("approved"));

        MvcResult login = mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andExpect(jsonPath("$.user.library.name").value("Central Library")).andReturn();
        String token = json(login).get("accessToken").asString();
        mvc.perform(getAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications", token)).andExpect(status().isOk());
        mvc.perform(getAuthed("/api/manage/libraries/" + other.getId() + "/members", token)).andExpect(status().isForbidden());
        mvc.perform(getAuthed("/api/platform/libraries", token)).andExpect(status().isForbidden());
    }

    @Test
    void superAdminCannotBeSelfRegistered() throws Exception {
        mvc.perform(postJson("/api/auth/register/super-admin", adminForm("erin@example.com", null))).andExpect(status().is4xxClientError());
        assertThat(users.findByEmail("erin@example.com")).isEmpty();
        mvc.perform(get("/api/auth/policy")).andExpect(jsonPath("$.superAdminRegistrationOpen").doesNotExist());
    }

    @Test
    void librarianApplicationsAtALibraryWithoutAdminOnlyReachActiveSuperAdmins() throws Exception {
        User boss = data.superAdmin("boss@example.com");
        User old = data.superAdmin("old-boss@example.com");
        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE id = ?", old.getId());
        Library orphan = data.library("ORP", "Orphan Library");
        mail.clear();
        mvc.perform(postJson("/api/auth/register/librarian", Map.of("fullName", "Libby Rao", "email", "libby@example.com",
                "password", TestData.PASSWORD, "confirmPassword", TestData.PASSWORD, "libraryId", orphan.getId(), "acceptTerms", true)))
                .andExpect(status().isCreated());
        assertThat(mail.latestTo(boss.getEmail())).hasValueSatisfying(m -> assertThat(m.subject()).contains("New librarian application"));
        assertThat(mail.latestTo(old.getEmail())).isEmpty();
    }

    @Test
    void adminApplicationsCanBeClosedByTheSuperAdmin() throws Exception {
        Library lib = data.library("CEN", "Central Library");
        String bossToken = data.token(data.superAdmin("boss@example.com"));
        mvc.perform(authed(put("/api/platform/config"), bossToken).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("registration.admin.open", "false"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/policy")).andExpect(jsonPath("$.adminRegistrationOpen").value(false));
        mvc.perform(postJson("/api/auth/register/admin", adminForm("dan@example.com", lib.getId())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REGISTRATION_CLOSED"));
    }
}
