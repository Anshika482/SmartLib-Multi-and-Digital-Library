package com.smartlib.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.ClientInfo;
import com.smartlib.security.PrincipalService;
import com.smartlib.security.RateLimiter;
import com.smartlib.security.TokenHasher;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;

/**
 * Email ownership verification with a 6-digit OTP and a secure link. Both are single-use, expire, are stored only as
 * hashes, and a new issuance supersedes the previous one (old OTP and old link stop working).
 */
@Service
public class VerificationService {

    private static final List<VerificationStatus> OPEN = List.of(VerificationStatus.ACTIVE, VerificationStatus.LOCKED);

    private final EmailVerificationRepository verifications;
    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final TokenHasher hasher;
    private final AppClock clock;
    private final AppProperties.Verification policy;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final PrincipalService principals;
    private final RateLimiter rateLimiter;

    public VerificationService(EmailVerificationRepository verifications, UserRepository users, StaffAssignmentRepository staff,
                               TokenHasher hasher, AppClock clock, AppProperties properties, ApplicationEventPublisher events,
                               AuditService audit, PrincipalService principals, RateLimiter rateLimiter) {
        this.verifications = verifications;
        this.users = users;
        this.staff = staff;
        this.hasher = hasher;
        this.clock = clock;
        this.policy = properties.verification();
        this.events = events;
        this.audit = audit;
        this.principals = principals;
        this.rateLimiter = rateLimiter;
    }

    public record Issued(long otpExpiresInSeconds, long resendAvailableInSeconds) {
    }

    /** Issues a fresh OTP + link (enforcing cooldown and daily limit) and emails them after commit. */
    @Transactional
    public Issued issue(User user) {
        Instant now = clock.now();
        verifications.findTopByUserIdOrderByCreatedAtDesc(user.getId()).ifPresent(last -> {
            long elapsed = Duration.between(last.getSentAt(), now).toSeconds();
            long cooldown = policy.resendCooldown().toSeconds();
            if (elapsed < cooldown) {
                throw ApiException.tooManyRequests(ErrorCodes.RESEND_COOLDOWN,
                        "Please wait " + (cooldown - elapsed) + " seconds before requesting another code.", cooldown - elapsed);
            }
        });
        Instant windowStart = now.minus(Duration.ofDays(1));
        long issuedToday = verifications.countByUserIdAndCreatedAtAfter(user.getId(), windowStart);
        if (issuedToday >= policy.maxResendsPerDay() + 1L) {
            long retry = verifications.findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(user.getId(), windowStart)
                    .map(first -> Math.max(60, Duration.between(now, first.getCreatedAt().plus(Duration.ofDays(1))).toSeconds()))
                    .orElse(3600L);
            throw ApiException.tooManyRequests(ErrorCodes.TOO_MANY_RESENDS,
                    "You have requested too many codes. Please try again later.", retry);
        }
        verifications.updateStatus(user.getId(), OPEN, VerificationStatus.SUPERSEDED);

        String otp = TokenHasher.newOtp();
        String token = TokenHasher.newToken();
        EmailVerification ev = new EmailVerification();
        ev.setUserId(user.getId());
        ev.setOtpHash(otpHash(user.getId(), otp));
        ev.setLinkTokenHash(TokenHasher.sha256Hex(token));
        ev.setOtpExpiresAt(now.plus(policy.otpTtl()));
        ev.setLinkExpiresAt(now.plus(policy.linkTtl()));
        ev.setStatus(VerificationStatus.ACTIVE);
        ev.setSentAt(now);
        ev.setCreatedAt(now);
        verifications.save(ev);

        events.publishEvent(new DomainEvents.VerificationEmailRequested(user.getId(), user.getEmail(), user.getFullName(), otp, token));
        return new Issued(policy.otpTtl().toSeconds(), policy.resendCooldown().toSeconds());
    }

    /** Resend for an email address. Unknown addresses get the same generic response. */
    @Transactional
    public AuthDtos.ResendResponse resend(String rawEmail) {
        String email = Texts.normalizeEmail(rawEmail);
        rateLimiter.check("resend:ip:" + ClientInfo.currentIp(), 20, Duration.ofHours(1));
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            return new AuthDtos.ResendResponse("If an account exists for this email, a new code has been sent.",
                    policy.otpTtl().toSeconds(), policy.resendCooldown().toSeconds());
        }
        if (user.isEmailVerified()) {
            throw ApiException.conflict(ErrorCodes.ALREADY_VERIFIED, "This email is already verified. You can log in.");
        }
        Issued issued = issue(user);
        audit.recordAs(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()), AuditActions.VERIFICATION_RESENT,
                "USER", user.getId(), null, null);
        return new AuthDtos.ResendResponse("A new verification code and link have been sent to your email.",
                issued.otpExpiresInSeconds(), issued.resendAvailableInSeconds());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthDtos.VerificationResult verifyOtp(String rawEmail, String otp) {
        rateLimiter.check("otp:ip:" + ClientInfo.currentIp(), 30, Duration.ofMinutes(10));
        String email = Texts.normalizeEmail(rawEmail);
        User user = users.findByEmail(email).orElse(null);
        if (user == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_OTP, "The code is incorrect. Please check and try again.");
        }
        if (user.isEmailVerified()) {
            throw ApiException.conflict(ErrorCodes.ALREADY_VERIFIED, "This email is already verified. You can log in.");
        }
        EmailVerification ev = verifications.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(user.getId(), OPEN)
                .orElseThrow(() -> ApiException.badRequest(ErrorCodes.NO_ACTIVE_CODE,
                        "There is no active code for this account. Please request a new one."));
        if (ev.getStatus() == VerificationStatus.LOCKED) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.TOO_MANY_OTP_ATTEMPTS,
                    "Too many incorrect attempts. Please request a new code.");
        }
        Instant now = clock.now();
        if (now.isAfter(ev.getOtpExpiresAt())) {
            throw ApiException.badRequest(ErrorCodes.OTP_EXPIRED, "This code has expired. Please request a new one.");
        }
        if (!TokenHasher.constantTimeEquals(ev.getOtpHash(), otpHash(user.getId(), otp))) {
            ev.setAttemptCount(ev.getAttemptCount() + 1);
            int remaining = Math.max(0, policy.maxAttempts() - ev.getAttemptCount());
            if (remaining == 0) {
                ev.setStatus(VerificationStatus.LOCKED);
            }
            verifications.save(ev);
            audit.recordIndependent(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()),
                    AuditActions.OTP_FAILED, "USER", user.getId(), null, "FAILURE", AuditService.details("attempt", ev.getAttemptCount()));
            if (remaining == 0) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.TOO_MANY_OTP_ATTEMPTS,
                        "Too many incorrect attempts. Please request a new code.");
            }
            throw ApiException.badRequest(ErrorCodes.INVALID_OTP, "The code is incorrect. Please check and try again.")
                    .with("attemptsRemaining", remaining);
        }
        return markVerified(user, ev, "OTP", now);
    }

    @Transactional
    public AuthDtos.VerificationResult verifyLink(String token) {
        rateLimiter.check("link:ip:" + ClientInfo.currentIp(), 30, Duration.ofMinutes(10));
        EmailVerification ev = verifications.findByLinkTokenHash(TokenHasher.sha256Hex(token))
                .orElseThrow(() -> ApiException.badRequest(ErrorCodes.INVALID_LINK, "This verification link is invalid."));
        User user = users.findById(ev.getUserId())
                .orElseThrow(() -> ApiException.badRequest(ErrorCodes.INVALID_LINK, "This verification link is invalid."));
        if (user.isEmailVerified()) {
            throw ApiException.conflict(ErrorCodes.ALREADY_VERIFIED, "This email is already verified. You can log in.")
                    .with("email", user.getEmail());
        }
        if (ev.getStatus() == VerificationStatus.SUPERSEDED || ev.getStatus() == VerificationStatus.CONSUMED) {
            throw ApiException.badRequest(ErrorCodes.INVALID_LINK,
                    "This link is no longer valid because a newer verification email was sent. Please use the latest email.");
        }
        Instant now = clock.now();
        if (now.isAfter(ev.getLinkExpiresAt())) {
            throw ApiException.badRequest(ErrorCodes.LINK_EXPIRED, "This verification link has expired. Please request a new one.")
                    .with("email", user.getEmail());
        }
        return markVerified(user, ev, "LINK", now);
    }

    private AuthDtos.VerificationResult markVerified(User user, EmailVerification ev, String via, Instant now) {
        user.setEmailVerifiedAt(now);
        users.save(user);
        ev.setStatus(VerificationStatus.CONSUMED);
        ev.setConsumedAt(now);
        ev.setConsumedVia(via);
        verifications.save(ev);
        verifications.updateStatus(user.getId(), List.of(VerificationStatus.ACTIVE, VerificationStatus.LOCKED), VerificationStatus.SUPERSEDED);
        principals.evict(user.getId());
        audit.recordAs(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()), AuditActions.EMAIL_VERIFIED,
                "USER", user.getId(), null, AuditService.details("via", via));
        events.publishEvent(new DomainEvents.UserVerified(user.getId()));

        boolean awaitingApproval = user.getRole() != UserRole.MEMBER && staff.findByUserId(user.getId())
                .map(a -> a.getApprovalStatus() == ApprovalStatus.PENDING).orElse(false);
        String waiting = switch (user.getRole()) {
            case ADMIN -> "Your admin application is now waiting for Super Admin approval.";
            case SUPER_ADMIN -> "Your Super Admin application is now waiting for approval by an existing Super Admin.";
            default -> "Your librarian application is now waiting for administrator approval.";
        };
        return new AuthDtos.VerificationResult("VERIFIED", user.getEmail(), user.getRole(),
                awaitingApproval ? "AWAIT_APPROVAL" : "LOGIN",
                awaitingApproval ? "Your email is verified. " + waiting : "Your email is verified. You can now log in.");
    }

    private String otpHash(long userId, String otp) {
        return hasher.pepperedHash(userId + ":" + otp);
    }
}
