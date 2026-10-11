package com.smartlib.auth;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.JwtService;
import com.smartlib.security.PrincipalService;
import com.smartlib.security.RateLimiter;
import com.smartlib.security.SuperAdminPolicy;
import com.smartlib.security.SmartLibPrincipal;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

@Service
public class AuthService {

    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final LibraryRepository libraries;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final PrincipalService principals;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AppClock clock;
    private final String dummyHash;

    private final SuperAdminPolicy superAdmins;

    public AuthService(UserRepository users, StaffAssignmentRepository staff, LibraryRepository libraries,
                       PasswordEncoder passwordEncoder, JwtService jwt, RefreshTokenService refreshTokens,
                       PrincipalService principals, AuditService audit, RateLimiter rateLimiter, AppClock clock,
                       SuperAdminPolicy superAdmins) {
        this.superAdmins = superAdmins;
        this.users = users;
        this.staff = staff;
        this.libraries = libraries;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.principals = principals;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("smartlib-timing-equalizer-" + System.nanoTime());
    }

    public record Session(String accessToken, String refreshToken, AuthDtos.Me me) {
    }

    /**
     * Credentials are checked first; account state (verification, approval, suspension) is only revealed to a caller
     * who supplied the correct password, so these checks cannot be used to discover accounts.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Session login(AuthDtos.LoginRequest req, String ip, String userAgent) {
        String email = Texts.normalizeEmail(req.email());
        rateLimiter.check("login:ip:" + ip, 30, Duration.ofMinutes(1));
        rateLimiter.check("login:email-ip:" + email + ":" + ip, 8, Duration.ofMinutes(5));
        rateLimiter.check("login:email:" + email, 50, Duration.ofHours(1));

        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(req.password(), dummyHash);
            audit.recordIndependent(new AuditService.Actor(null, null, email), AuditActions.LOGIN_FAILED, "USER", null, null,
                    "FAILURE", AuditService.details("reason", "unknown_email"));
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            audit.recordIndependent(actor(user), AuditActions.LOGIN_FAILED, "USER", user.getId(), null, "FAILURE",
                    AuditService.details("reason", "bad_password"));
            throw invalidCredentials();
        }
        if (user.getStatus() == UserStatus.INVITED) {
            throw invalidCredentials();
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            blocked(user, "suspended");
            throw ApiException.forbidden(ErrorCodes.ACCOUNT_SUSPENDED, "Your account has been suspended. Please contact support.");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            blocked(user, "disabled");
            throw ApiException.forbidden(ErrorCodes.ACCOUNT_DISABLED, "This account has been disabled.");
        }
        if (user.getRole() == UserRole.SUPER_ADMIN && !superAdmins.allows(user.getEmail())) {
            // Single Super Admin: only the account configured in .env may sign in as SUPER_ADMIN.
            blocked(user, "not_the_configured_super_admin");
            throw ApiException.forbidden(ErrorCodes.ACCOUNT_DISABLED, "This account has been disabled.");
        }
        if (!user.isEmailVerified()) {
            blocked(user, "email_not_verified");
            throw ApiException.forbidden(ErrorCodes.EMAIL_NOT_VERIFIED, "Your email is not verified yet.")
                    .with("email", user.getEmail());
        }
        if (user.isStaff() || user.getRole() == UserRole.SUPER_ADMIN) {
            StaffAssignment assignment = staff.findByUserId(user.getId()).orElse(null);
            if (assignment == null) {
                // Seeded, bootstrapped or invited SUPER_ADMINs have no application to approve.
                if (user.isStaff()) {
                    throw ApiException.forbidden(ErrorCodes.STAFF_ACCESS_REVOKED, "Your staff account is not assigned to a library.");
                }
            } else {
                requireApproved(user, assignment);
            }
        }
        user.setLastLoginAt(clock.now());
        users.save(user);
        SmartLibPrincipal principal = principals.toPrincipal(user);
        principals.evict(user.getId());
        audit.recordAs(actor(user), AuditActions.LOGIN_SUCCEEDED, "USER", user.getId(), principal.libraryId(), null);
        String refresh = refreshTokens.issue(user.getId(), null, ip, userAgent);
        return new Session(jwt.issueAccessToken(principal), refresh, toMe(principal, user));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String rawRefresh, String ip, String userAgent) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefresh, ip, userAgent);
        User user = users.findById(rotation.userId()).orElseThrow(() -> ApiException.unauthorized(ErrorCodes.INVALID_REFRESH_TOKEN,
                "Your session has expired. Please log in again."));
        SmartLibPrincipal principal = principals.toPrincipal(user);
        if (!principal.canAuthenticate()) {
            refreshTokens.revokeAll(user.getId());
            throw ApiException.unauthorized(ErrorCodes.INVALID_REFRESH_TOKEN, "Your session is no longer valid. Please log in again.");
        }
        return new Session(jwt.issueAccessToken(principal), rotation.rawToken(), toMe(principal, user));
    }

    @Transactional
    public void logout(String rawRefresh) {
        refreshTokens.revokeFamilyOf(rawRefresh);
    }

    @Transactional
    public void logoutEverywhere(long userId) {
        User user = users.findById(userId).orElseThrow();
        user.bumpTokenVersion();
        users.save(user);
        refreshTokens.revokeAll(userId);
        principals.evict(userId);
        audit.record(AuditActions.LOGOUT, "USER", userId, null, AuditService.details("scope", "all_devices"));
    }

    /** Issues a fresh session for an already-authenticated user (e.g. after a password change). */
    @Transactional
    public Session newSession(User user, String ip, String userAgent) {
        SmartLibPrincipal principal = principals.toPrincipal(user);
        String refresh = refreshTokens.issue(user.getId(), null, ip, userAgent);
        return new Session(jwt.issueAccessToken(principal), refresh, toMe(principal, user));
    }

    public AuthDtos.Me me(long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized(ErrorCodes.UNAUTHORIZED, "Please log in."));
        return toMe(principals.toPrincipal(user), user);
    }

    public AuthDtos.Me toMe(SmartLibPrincipal principal, User user) {
        AuthDtos.LibraryRef library = principal.libraryId() == null ? null
                : libraries.findById(principal.libraryId()).map(l -> new AuthDtos.LibraryRef(l.getId(), l.getName(), l.getSlug())).orElse(null);
        return new AuthDtos.Me(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(), user.getRole(),
                user.isEmailVerified(), library, principal.approvalStatus(), user.getCreatedAt());
    }

    /** Blocks sign-in until the LIBRARIAN / ADMIN / SUPER_ADMIN application is approved. */
    private void requireApproved(User user, StaffAssignment assignment) {
        String libraryName = assignment.getLibraryId() == null ? null
                : libraries.findById(assignment.getLibraryId()).map(l -> l.getName()).orElse(null);
        boolean librarian = user.getRole() == UserRole.LIBRARIAN;
        String what = switch (user.getRole()) {
            case LIBRARIAN -> "librarian";
            case ADMIN -> "admin";
            default -> "Super Admin";
        };
        String approver = librarian ? "administrator" : user.getRole() == UserRole.ADMIN ? "Super Admin" : "existing Super Admin";
        switch (assignment.getApprovalStatus()) {
            case PENDING -> {
                blocked(user, "approval_pending");
                throw ApiException.forbidden(librarian ? ErrorCodes.LIBRARIAN_APPROVAL_PENDING : ErrorCodes.APPROVAL_PENDING,
                                "Your " + what + " application is waiting for " + approver + " approval.")
                        .with("role", user.getRole().name()).with("libraryName", libraryName).with("appliedAt", assignment.getCreatedAt());
            }
            case REJECTED -> {
                blocked(user, "approval_rejected");
                throw ApiException.forbidden(librarian ? ErrorCodes.LIBRARIAN_REJECTED : ErrorCodes.APPLICATION_REJECTED,
                                "Your " + what + " application was not approved.")
                        .with("role", user.getRole().name()).with("libraryName", libraryName)
                        .with("reason", assignment.getRejectionReason()).with("reviewedAt", assignment.getReviewedAt());
            }
            case REVOKED -> {
                blocked(user, "staff_revoked");
                throw ApiException.forbidden(ErrorCodes.STAFF_ACCESS_REVOKED,
                        "Your " + what + " access has been revoked. Please contact " + (librarian ? "your library administrator." : "a Super Admin."));
            }
            case APPROVED -> {
                // continue
            }
        }
    }

    private void blocked(User user, String reason) {
        audit.recordIndependent(actor(user), AuditActions.LOGIN_BLOCKED, "USER", user.getId(), null, "FAILURE",
                AuditService.details("reason", reason));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_CREDENTIALS, "Incorrect email or password.");
    }

    private static AuditService.Actor actor(User user) {
        return new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail());
    }
}
