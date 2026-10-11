package com.smartlib.auth;

import java.time.Duration;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.ClientInfo;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.platform.PlatformSettings;
import com.smartlib.security.RateLimiter;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/**
 * Public self-registration. MEMBER accounts are usable once the email is verified. LIBRARIAN, ADMIN and SUPER_ADMIN
 * registrations are applications: the account cannot sign in until the email is verified AND the application is
 * approved (LIBRARIAN by the library's ADMIN, ADMIN only by the SUPER_ADMIN). The single SUPER_ADMIN is configured in
 * .env (see BootstrapRunner) and is never self-registered.
 */
@Service
public class RegistrationService {

    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final LibraryRepository libraries;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final VerificationService verification;
    private final AuditService audit;
    private final AppClock clock;
    private final RateLimiter rateLimiter;
    private final PlatformSettings settings;
    private final ApplicationEventPublisher events;

    public RegistrationService(UserRepository users, StaffAssignmentRepository staff, LibraryRepository libraries,
                               PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, VerificationService verification,
                               AuditService audit, AppClock clock, RateLimiter rateLimiter, PlatformSettings settings,
                               ApplicationEventPublisher events) {
        this.users = users;
        this.staff = staff;
        this.libraries = libraries;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.verification = verification;
        this.audit = audit;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.events = events;
    }

    @Transactional
    public AuthDtos.RegistrationResponse registerMember(AuthDtos.RegisterMemberRequest req) {
        rateLimiter.check("register:ip:" + ClientInfo.currentIp(), 10, Duration.ofHours(1));
        String email = Texts.normalizeEmail(req.email());
        passwordPolicy.validate(req.password(), req.confirmPassword(), email, "password", "confirmPassword");
        User user = createUser(email, req.fullName(), req.phone(), req.password(), UserRole.MEMBER);
        VerificationService.Issued issued = verification.issue(user);
        audit.recordAs(actor(user), AuditActions.USER_REGISTERED, "USER", user.getId(), null, AuditService.details("role", "MEMBER"));
        return new AuthDtos.RegistrationResponse(user.getEmail(), "MEMBER", issued.otpExpiresInSeconds(),
                issued.resendAvailableInSeconds(), "VERIFY_EMAIL",
                "Account created. We sent a 6-digit code and a verification link to your email.");
    }

    @Transactional
    public AuthDtos.RegistrationResponse registerLibrarian(AuthDtos.RegisterLibrarianRequest req) {
        rateLimiter.check("register:ip:" + ClientInfo.currentIp(), 10, Duration.ofHours(1));
        if (!settings.isEnabled(PlatformSettings.LIBRARIAN_REGISTRATION_OPEN)) {
            throw ApiException.forbidden(ErrorCodes.LIBRARIAN_REGISTRATION_CLOSED, "Librarian applications are currently closed.");
        }
        Library library = libraries.findById(req.libraryId()).filter(Library::isActive)
                .orElseThrow(() -> ApiException.validation("libraryId", "Please choose an active library"));
        String email = Texts.normalizeEmail(req.email());
        passwordPolicy.validate(req.password(), req.confirmPassword(), email, "password", "confirmPassword");
        User user = createUser(email, req.fullName(), req.phone(), req.password(), UserRole.LIBRARIAN);

        application(user, library.getId(), null, req.employeeRef(), req.applicationNote());

        VerificationService.Issued issued = verification.issue(user);
        audit.recordAs(actor(user), AuditActions.LIBRARIAN_APPLIED, "USER", user.getId(), library.getId(),
                AuditService.details("library", library.getName()));
        events.publishEvent(new DomainEvents.LibrarianApplied(user.getId(), library.getId()));
        return new AuthDtos.RegistrationResponse(user.getEmail(), "LIBRARIAN", issued.otpExpiresInSeconds(),
                issued.resendAvailableInSeconds(), "VERIFY_EMAIL",
                "Application submitted. Verify your email first; an administrator of " + library.getName()
                        + " will then review your application.");
    }

    @Transactional
    public AuthDtos.RegistrationResponse registerAdmin(AuthDtos.RegisterAdminRequest req) {
        rateLimiter.check("register:ip:" + ClientInfo.currentIp(), 10, Duration.ofHours(1));
        if (!settings.isEnabled(PlatformSettings.ADMIN_REGISTRATION_OPEN)) {
            throw ApiException.forbidden(ErrorCodes.ADMIN_REGISTRATION_CLOSED, "Admin applications are currently closed.");
        }
        Library library = libraries.findById(req.libraryId()).filter(Library::isActive)
                .orElseThrow(() -> ApiException.validation("libraryId", "Please choose an active library"));
        String email = Texts.normalizeEmail(req.email());
        passwordPolicy.validate(req.password(), req.confirmPassword(), email, "password", "confirmPassword");
        User user = createUser(email, req.fullName(), req.phone(), req.password(), UserRole.ADMIN);
        application(user, library.getId(), req.designation(), req.employeeRef(), req.applicationNote());

        VerificationService.Issued issued = verification.issue(user);
        audit.recordAs(actor(user), AuditActions.ADMIN_APPLIED, "USER", user.getId(), library.getId(),
                AuditService.details("library", library.getName()));
        events.publishEvent(new DomainEvents.AdminApplied(user.getId(), library.getId(), UserRole.ADMIN.name()));
        return new AuthDtos.RegistrationResponse(user.getEmail(), "ADMIN", issued.otpExpiresInSeconds(),
                issued.resendAvailableInSeconds(), "VERIFY_EMAIL",
                "Application submitted. Verify your email first; a Super Admin will then review your request to administer "
                        + library.getName() + ".");
    }

    /** A PENDING approval row for a LIBRARIAN or ADMIN application. */
    private void application(User user, Long libraryId, String designation, String employeeRef, String note) {
        StaffAssignment assignment = new StaffAssignment();
        assignment.setUserId(user.getId());
        assignment.setLibraryId(libraryId);
        assignment.setApprovalStatus(ApprovalStatus.PENDING);
        assignment.setOrigin(StaffAssignment.ORIGIN_APPLICATION);
        assignment.setDesignation(Texts.trimToNull(designation));
        assignment.setEmployeeRef(Texts.trimToNull(employeeRef));
        assignment.setApplicationNote(Texts.trimToNull(note));
        staff.save(assignment);
    }

    private User createUser(String email, String fullName, String phone, String password, UserRole role) {
        if (users.existsByEmail(email)) {
            boolean verified = users.findByEmail(email).map(User::isEmailVerified).orElse(true);
            throw ApiException.conflict(ErrorCodes.EMAIL_ALREADY_REGISTERED,
                            "An account with this email already exists. Try logging in or resetting your password.")
                    .with("unverified", verified ? null : Boolean.TRUE);
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName.trim());
        user.setPhone(Texts.trimToNull(phone));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setTermsAcceptedAt(clock.now());
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(ErrorCodes.EMAIL_ALREADY_REGISTERED, "An account with this email already exists.");
        }
    }

    private static AuditService.Actor actor(User user) {
        return new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail());
    }
}
