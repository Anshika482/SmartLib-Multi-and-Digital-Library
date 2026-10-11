package com.smartlib.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
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
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.PrincipalService;
import com.smartlib.security.TokenHasher;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/** ADMIN accounts are provisioned by a SUPER_ADMIN via a single-use set-password invitation (never a public sign-up). */
@Service
public class InviteService {

    private static final Duration INVITE_TTL = Duration.ofHours(72);

    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final LibraryRepository libraries;
    private final AccountTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final PrincipalService principals;
    private final AppClock clock;

    public InviteService(UserRepository users, StaffAssignmentRepository staff, LibraryRepository libraries,
                         AccountTokenRepository tokens, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                         AuditService audit, ApplicationEventPublisher events, PrincipalService principals, AppClock clock) {
        this.users = users;
        this.staff = staff;
        this.libraries = libraries;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.events = events;
        this.principals = principals;
        this.clock = clock;
    }

    @Transactional
    public long inviteAdmin(String rawEmail, String fullName, long libraryId, long invitedBy) {
        String email = Texts.normalizeEmail(rawEmail);
        Library library = libraries.findById(libraryId)
                .orElseThrow(() -> ApiException.validation("libraryId", "Library not found"));
        if (users.existsByEmail(email)) {
            throw ApiException.conflict(ErrorCodes.EMAIL_ALREADY_REGISTERED, "An account with this email already exists.");
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName.trim());
        user.setRole(UserRole.ADMIN);
        user.setStatus(UserStatus.INVITED);
        user.setPasswordHash(passwordEncoder.encode(TokenHasher.newToken()));
        users.saveAndFlush(user);

        StaffAssignment assignment = new StaffAssignment();
        assignment.setUserId(user.getId());
        assignment.setLibraryId(libraryId);
        assignment.setApprovalStatus(ApprovalStatus.APPROVED);
        assignment.setReviewedBy(invitedBy);
        assignment.setReviewedAt(clock.now());
        staff.save(assignment);

        String raw = TokenHasher.newToken();
        AccountToken token = new AccountToken();
        token.setUserId(user.getId());
        token.setPurpose(AccountTokenPurpose.INVITE);
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(clock.now().plus(INVITE_TTL));
        token.setCreatedAt(clock.now());
        tokens.save(token);

        audit.record(AuditActions.ADMIN_INVITED, "USER", user.getId(), libraryId, AuditService.details("email", email));
        events.publishEvent(new DomainEvents.AdminInvited(user.getId(), email, user.getFullName(), library.getName(), raw));
        return user.getId();
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenValidity validate(String raw) {
        AccountToken token = tokens.peek(TokenHasher.sha256Hex(raw), AccountTokenPurpose.INVITE).orElse(null);
        if (token == null || token.getUsedAt() != null || clock.now().isAfter(token.getExpiresAt())) {
            return new AuthDtos.TokenValidity(false, ErrorCodes.INVALID_INVITE, null, null, null);
        }
        User user = users.findById(token.getUserId()).orElse(null);
        if (user == null) {
            return new AuthDtos.TokenValidity(false, ErrorCodes.INVALID_INVITE, null, null, null);
        }
        String libraryName = staff.findByUserId(user.getId()).filter(a -> a.getLibraryId() != null).flatMap(a -> libraries.findById(a.getLibraryId()))
                .map(Library::getName).orElse(null);
        return new AuthDtos.TokenValidity(true, null, user.getEmail(), user.getFullName(), libraryName);
    }

    @Transactional
    public void accept(String raw, String password, String confirm) {
        AccountToken token = tokens.findByTokenHashAndPurpose(TokenHasher.sha256Hex(raw), AccountTokenPurpose.INVITE)
                .orElseThrow(() -> ApiException.badRequest(ErrorCodes.INVALID_INVITE, "This invitation link is invalid."));
        Instant now = clock.now();
        if (token.getUsedAt() != null || now.isAfter(token.getExpiresAt())) {
            throw ApiException.badRequest(ErrorCodes.INVALID_INVITE, "This invitation link has expired or was already used.");
        }
        User user = users.findById(token.getUserId()).orElseThrow();
        passwordPolicy.validate(password, confirm, user.getEmail(), "password", "confirmPassword");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(now);
        user.setTermsAcceptedAt(now);
        user.bumpTokenVersion();
        users.save(user);
        token.setUsedAt(now);
        tokens.save(token);
        principals.evict(user.getId());
        audit.recordAs(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()), AuditActions.INVITE_ACCEPTED,
                "USER", user.getId(), null, null);
    }
}
