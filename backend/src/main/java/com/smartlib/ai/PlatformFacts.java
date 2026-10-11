package com.smartlib.ai;

import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.smartlib.auth.PasswordPolicy;
import com.smartlib.common.config.AppProperties;
import com.smartlib.library.LibraryQueryService;
import com.smartlib.platform.PlatformSettings;

/** Authoritative how-to facts for the assistant, generated from live configuration (never hand-maintained numbers). */
@Component
public class PlatformFacts {

    private final AppProperties properties;
    private final PlatformSettings settings;
    private final LibraryQueryService libraries;

    public PlatformFacts(AppProperties properties, PlatformSettings settings, LibraryQueryService libraries) {
        this.properties = properties;
        this.settings = settings;
        this.libraries = libraries;
    }

    public String build() {
        var v = properties.verification();
        long otpMinutes = v.otpTtl().toMinutes();
        long linkHours = v.linkTtl().toHours();
        long resetMinutes = properties.passwordReset().ttl().toMinutes();
        String libs = libraries.list(null, null).stream()
                .map(l -> l.name() + (l.city() == null ? "" : " (" + l.city() + ")"))
                .collect(Collectors.joining(", "));
        boolean librarianOpen = settings.isEnabled(PlatformSettings.LIBRARIAN_REGISTRATION_OPEN);
        return """
                - SmartLib is one platform with several independent libraries. Current libraries: %s.
                - Anyone can browse and search the combined catalogue of all libraries at /catalogue without logging in. Search covers title, author and ISBN across every library unless a library filter is chosen. Each book page (/catalogue/<id>) shows availability separately for every library that holds it.
                - Borrowing is always from one specific library: on the book page the member compares libraries and clicks "Request from <library>". Logging in is required to request.
                - Member registration: /register with full name, email, optional phone, password and accepting the terms. Passwords need at least %d characters including a letter and a number.
                - Email verification: after registering, SmartLib emails a 6-digit code (OTP) and a secure verification link. Either one verifies the email on /verify-email. The code is valid for %d minutes and the link for %d hours. Each is single-use.
                - Wrong codes: at most %d wrong attempts per code, then a new code must be requested.
                - Resending: "Resend code" on /verify-email works after a %d-second cooldown, up to %d times per 24 hours. Every new email cancels the previous code and link. If the email doesn't arrive: check spam/promotions, confirm the address is correct, wait for the cooldown and resend.
                - Login requires a verified email. Unverified users are sent to the verification screen.
                - Librarian accounts: apply at /register/librarian (choose your library), verify your email, then wait for that library's administrator to approve the application. Login is possible only after approval. Applications are currently %s.
                - Administrator accounts cannot be self-registered; platform administrators invite them.
                - Forgot password: /forgot-password sends a reset link valid for %d minutes (single use). For privacy the page shows the same message whether or not the email exists.
                - Request lifecycle: Pending (librarian reviews) -> Approved (a copy is reserved; collect it within the library's pickup window) -> Issued/Borrowed (due date set by the library's loan period) -> Returned. Members can cancel pending or approved requests at /app/member/requests. Borrowed books and due dates: /app/member/borrowings.
                - Returning: bring the physical book back to the desk of the same library before the due date; the librarian records the return.
                - Fines: late returns create an overdue fine (library's fine per day, after any grace days, capped per loan). Lost or damaged books can also be charged. Fines: /app/member/fines. Pay online with Razorpay (UPI, cards, netbanking). A fine shows as paid only after SmartLib's server verifies the payment. Payment history: /app/member/payments. Large unpaid fines can block new requests at that library.
                - Digital resources (PDF, EPUB, video, links): /app/member/resources. Some are public, some need login, some are only for members of the owning library (you become a member by joining the library or by requesting a book there).
                - Notifications: /app/member/notifications. Dashboards: members /app/member, librarians /app/librarian, library admins /app/admin, platform super admin /app/super-admin.
                - Roles: MEMBER, LIBRARIAN (one library), ADMIN (one library), SUPER_ADMIN (whole platform).
                """.formatted(libs.isBlank() ? "none yet" : libs, PasswordPolicy.MIN_LENGTH, otpMinutes, linkHours, v.maxAttempts(),
                v.resendCooldown().toSeconds(), v.maxResendsPerDay(), librarianOpen ? "open" : "closed", resetMinutes);
    }
}
