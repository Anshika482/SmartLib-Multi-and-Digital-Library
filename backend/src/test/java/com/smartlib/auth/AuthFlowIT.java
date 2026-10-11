package com.smartlib.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.smartlib.library.Library;
import com.smartlib.support.IntegrationTest;
import com.smartlib.support.TestData;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.User;
import com.smartlib.user.UserRole;

import tools.jackson.databind.JsonNode;

class AuthFlowIT extends IntegrationTest {

    private Map<String, Object> memberForm(String email) {
        return Map.of("fullName", "Asha Verma", "email", email, "phone", "+91 98765 43210",
                "password", TestData.PASSWORD, "confirmPassword", TestData.PASSWORD, "acceptTerms", true);
    }

    @Test
    void memberRegistration_otpVerification_login_refreshRotation_logout() throws Exception {
        String email = "asha@example.com";
        mvc.perform(postJson("/api/auth/register/member", memberForm("Asha@Example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.nextStep").value("VERIFY_EMAIL"))
                .andExpect(jsonPath("$.otpExpiresInSeconds").value(600));

        // Unverified accounts cannot log in
        mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.email").value(email));

        // Wrong OTP is counted
        String otp = mail.latestOtp(email);
        String wrong = otp.equals("000000") ? "111111" : "000000";
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", wrong)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OTP"))
                .andExpect(jsonPath("$.attemptsRemaining").value(4));

        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", otp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.nextStep").value("LOGIN"));

        // OTP is single-use
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", otp)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));

        // No secrets stored in plaintext
        String storedHash = jdbc.queryForObject("SELECT otp_hash FROM email_verifications LIMIT 1", String.class);
        assertThat(storedHash).hasSize(64).doesNotContain(otp);

        MvcResult login = mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("MEMBER"))
                .andReturn();
        String access = json(login).get("accessToken").asString();
        Cookie refresh = refreshCookie(login);
        assertThat(refresh.isHttpOnly()).isTrue();

        mvc.perform(getAuthed("/api/auth/me", access)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

        // Refresh requires the CSRF header
        mvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isForbidden());
        MvcResult rotated = mvc.perform(post("/api/auth/refresh").cookie(refresh).header("X-Requested-With", "SmartLib"))
                .andExpect(status().isOk()).andReturn();
        Cookie refresh2 = refreshCookie(rotated);
        assertThat(refresh2.getValue()).isNotEqualTo(refresh.getValue());

        // Re-using the old token after the grace window = theft -> whole family revoked
        jdbc.update("UPDATE refresh_tokens SET revoked_at = DATE_SUB(revoked_at, INTERVAL 1 MINUTE) WHERE revoked_at IS NOT NULL");
        mvc.perform(post("/api/auth/refresh").cookie(refresh).header("X-Requested-With", "SmartLib"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/auth/refresh").cookie(refresh2).header("X-Requested-With", "SmartLib"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialsAreGeneric() throws Exception {
        data.member("real@example.com");
        mvc.perform(postJson("/api/auth/login", Map.of("email", "real@example.com", "password", "wrongPass1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(postJson("/api/auth/login", Map.of("email", "nobody@example.com", "password", "wrongPass1")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        data.member("taken@example.com");
        mvc.perform(postJson("/api/auth/register/member", memberForm("taken@example.com")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mvc.perform(postJson("/api/auth/register/member", Map.of("fullName", "X Y", "email", "weak@example.com",
                        "password", "password", "confirmPassword", "password", "acceptTerms", true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        mvc.perform(postJson("/api/auth/register/member", Map.of("fullName", "X Y", "email", "terms@example.com",
                        "password", TestData.PASSWORD, "confirmPassword", TestData.PASSWORD, "acceptTerms", false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("acceptTerms"));
    }

    @Test
    void resendCooldown_supersedesPreviousOtp_andLinkVerification() throws Exception {
        String email = "link@example.com";
        mvc.perform(postJson("/api/auth/register/member", memberForm(email))).andExpect(status().isCreated());
        String firstOtp = mail.latestOtp(email);
        String firstToken = mail.latestToken(email, "/verify-email");

        mvc.perform(postJson("/api/auth/resend-verification", Map.of("email", email)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RESEND_COOLDOWN"));

        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61)));
        mvc.perform(postJson("/api/auth/resend-verification", Map.of("email", email))).andExpect(status().isAccepted());
        String secondToken = mail.latestToken(email, "/verify-email");
        assertThat(secondToken).isNotEqualTo(firstToken);

        // The old link was superseded
        mvc.perform(postJson("/api/auth/verify-email/link", Map.of("token", firstToken)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LINK"));
        // The old OTP no longer works either (unless the new random OTP happens to be identical)
        String secondOtp = mail.latestOtp(email);
        if (!secondOtp.equals(firstOtp)) {
            mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", firstOtp)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(postJson("/api/auth/verify-email/link", Map.of("token", secondToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        mvc.perform(postJson("/api/auth/verify-email/link", Map.of("token", secondToken)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
    }

    @Test
    void otpExpiresAndLocksAfterTooManyAttempts() throws Exception {
        String email = "lock@example.com";
        mvc.perform(postJson("/api/auth/register/member", memberForm(email))).andExpect(status().isCreated());
        String otp = mail.latestOtp(email);
        String wrong = otp.equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 4; i++) {
            mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", wrong)))
                    .andExpect(jsonPath("$.code").value("INVALID_OTP"));
        }
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", wrong)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_OTP_ATTEMPTS"));
        // Even the correct code is refused once locked
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", otp)))
                .andExpect(jsonPath("$.code").value("TOO_MANY_OTP_ATTEMPTS"));

        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)));
        mvc.perform(postJson("/api/auth/resend-verification", Map.of("email", email))).andExpect(status().isAccepted());
        String fresh = mail.latestOtp(email);
        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(13)));
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", fresh)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
    }

    @Test
    void librarianNeedsVerificationThenApproval() throws Exception {
        Library lib = data.library("CEN", "Central Library");
        Library other = data.library("WST", "West End Library");
        String email = "libby@example.com";
        mvc.perform(postJson("/api/auth/register/librarian", Map.of("fullName", "Libby Rao", "email", email,
                        "password", TestData.PASSWORD, "confirmPassword", TestData.PASSWORD, "libraryId", lib.getId(),
                        "acceptTerms", true)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("LIBRARIAN"));

        mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mvc.perform(postJson("/api/auth/verify-email/otp", Map.of("email", email, "otp", mail.latestOtp(email))))
                .andExpect(jsonPath("$.nextStep").value("AWAIT_APPROVAL"));
        mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LIBRARIAN_APPROVAL_PENDING"))
                .andExpect(jsonPath("$.libraryName").value("Central Library"));

        long assignmentId = jdbc.queryForObject("SELECT id FROM staff_assignments", Long.class);
        User otherAdmin = data.staff("admin-west@example.com", UserRole.ADMIN, other.getId(), ApprovalStatus.APPROVED);
        User centralLibrarian = data.staff("lib-central@example.com", UserRole.LIBRARIAN, lib.getId(), ApprovalStatus.APPROVED);
        User centralAdmin = data.staff("admin-central@example.com", UserRole.ADMIN, lib.getId(), ApprovalStatus.APPROVED);

        // An admin of another library cannot approve
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + assignmentId + "/approve",
                data.token(otherAdmin), Map.of())).andExpect(status().isForbidden());
        // Nor via their own library path (application belongs to another library -> not found)
        mvc.perform(postAuthed("/api/manage/libraries/" + other.getId() + "/librarian-applications/" + assignmentId + "/approve",
                data.token(otherAdmin), Map.of())).andExpect(status().isNotFound());
        // A librarian (non-admin) cannot approve
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + assignmentId + "/approve",
                data.token(centralLibrarian), Map.of())).andExpect(status().isForbidden());

        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + assignmentId + "/approve",
                data.token(centralAdmin), Map.of())).andExpect(status().isNoContent());

        MvcResult login = mvc.perform(postJson("/api/auth/login", Map.of("email", email, "password", TestData.PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.library.name").value("Central Library")).andReturn();
        String token = json(login).get("accessToken").asString();
        mvc.perform(getAuthed("/api/manage/libraries/" + lib.getId() + "/members", token)).andExpect(status().isOk());
        mvc.perform(getAuthed("/api/manage/libraries/" + other.getId() + "/members", token)).andExpect(status().isForbidden());
        mvc.perform(getAuthed("/api/platform/libraries", token)).andExpect(status().isForbidden());
    }

    @Test
    void rejectedLibrarianSeesReason() throws Exception {
        Library lib = data.library("CEN", "Central Library");
        User applicant = data.staff("rej@example.com", UserRole.LIBRARIAN, lib.getId(), ApprovalStatus.PENDING);
        User admin = data.staff("adm@example.com", UserRole.ADMIN, lib.getId(), ApprovalStatus.APPROVED);
        long id = jdbc.queryForObject("SELECT id FROM staff_assignments WHERE user_id = ?", Long.class, applicant.getId());
        mvc.perform(postAuthed("/api/manage/libraries/" + lib.getId() + "/librarian-applications/" + id + "/reject",
                data.token(admin), Map.of("reason", "Not on staff roster"))).andExpect(status().isNoContent());
        mvc.perform(postJson("/api/auth/login", Map.of("email", "rej@example.com", "password", TestData.PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LIBRARIAN_REJECTED"))
                .andExpect(jsonPath("$.reason").value("Not on staff roster"));
    }

    @Test
    void passwordResetIsGenericSingleUseAndRevokesSessions() throws Exception {
        User u = data.member("reset@example.com");
        String oldToken = data.token(u);
        mvc.perform(postJson("/api/auth/forgot-password", Map.of("email", "nobody@example.com")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(PasswordResetService.GENERIC_MESSAGE));
        assertThat(mail.latestTo("nobody@example.com")).isEmpty();
        mvc.perform(postJson("/api/auth/forgot-password", Map.of("email", "reset@example.com")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(PasswordResetService.GENERIC_MESSAGE));
        String token = mail.latestToken("reset@example.com", "/reset-password");
        mvc.perform(postJson("/api/auth/reset-password/validate", Map.of("token", token))).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "N3wPassword!x",
                "confirmPassword", "N3wPassword!x"))).andExpect(status().isOk());
        mvc.perform(postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "An0ther!pass",
                        "confirmPassword", "An0ther!pass")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        // old access token revoked (token version bumped)
        mvc.perform(getAuthed("/api/auth/me", oldToken)).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/auth/login", Map.of("email", "reset@example.com", "password", "N3wPassword!x")))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRequireValidTokens() throws Exception {
        mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(getAuthed("/api/me/profile", "not-a-jwt")).andExpect(status().isUnauthorized());
        // alg=none token
        String none = "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIiwiaXNzIjoic21hcnRsaWIiLCJ2ZXIiOjB9.";
        mvc.perform(getAuthed("/api/me/profile", none)).andExpect(status().isUnauthorized());
        User member = data.member("m@example.com");
        String token = data.token(member);
        mvc.perform(getAuthed("/api/manage/libraries/1/members", token)).andExpect(status().isForbidden());
        mvc.perform(getAuthed("/api/platform/users", token)).andExpect(status().isForbidden());
        // suspended accounts lose access immediately
        jdbc.update("UPDATE users SET status = 'SUSPENDED' WHERE id = ?", member.getId());
        principals.evict(member.getId());
        mvc.perform(getAuthed("/api/me/profile", token)).andExpect(status().isUnauthorized());
        JsonNode policy = json(mvc.perform(get("/api/auth/policy")).andReturn());
        assertThat(policy.get("otpTtlSeconds").asLong()).isEqualTo(600);
    }
}
