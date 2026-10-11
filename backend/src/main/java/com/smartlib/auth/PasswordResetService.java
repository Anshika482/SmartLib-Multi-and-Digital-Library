package com.smartlib.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserStatus;

/** Forgot/reset password with a single-use, expiring, hashed link token. Responses never reveal whether an email exists. */
@Service
public class PasswordResetService {

    public static final String GENERIC_MESSAGE =
            "If an account exists for this email, we've sent a password reset link. It expires in 30 minutes.";

    private final AccountTokenRepository tokens;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokens;
    private final PrincipalService principals;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final RateLimiter rateLimiter;
    private final AppClock clock;
    private final Duration ttl;

    public PasswordResetService(AccountTokenRepository tokens, UserRepository users, PasswordEncoder passwordEncoder,
                                PasswordPolicy passwordPolicy, RefreshTokenService refreshTokens, PrincipalService principals,
                                AuditService audit, ApplicationEventPublisher events, RateLimiter rateLimiter, AppClock clock,
                                AppProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokens = refreshTokens;
        this.principals = principals;
        this.audit = audit;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.ttl = properties.passwordReset().ttl();
    }

    @Transactional
    public void requestReset(String rawEmail) {
        String email = Texts.normalizeEmail(rawEmail);
        String ip = ClientInfo.currentIp();
        rateLimiter.check("forgot:ip:" + ip, 10, Duration.ofHours(1));
        rateLimiter.check("forgot:email:" + email, 3, Duration.ofHours(1));
        User user = users.findByEmail(email).orElse(null);
        if (user == null || (user.getStatus() != UserStatus.ACTIVE)) {
            return;
        }
        Instant now = clock.now();
        tokens.invalidateOutstanding(user.getId(), AccountTokenPurpose.PASSWORD_RESET, now);
        String raw = TokenHasher.newToken();
        AccountToken token = new AccountToken();
        token.setUserId(user.getId());
        token.setPurpose(AccountTokenPurpose.PASSWORD_RESET);
        token.setTokenHash(TokenHasher.sha256Hex(raw));
        token.setExpiresAt(now.plus(ttl));
        token.setRequestedIp(ip);
        token.setCreatedAt(now);
        tokens.save(token);
        audit.recordAs(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()),
                AuditActions.PASSWORD_RESET_REQUESTED, "USER", user.getId(), null, null);
        events.publishEvent(new DomainEvents.PasswordResetRequested(user.getId(), user.getEmail(), user.getFullName(), raw));
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenValidity validate(String raw) {
        AccountToken token = tokens.peek(TokenHasher.sha256Hex(raw), AccountTokenPurpose.PASSWORD_RESET).orElse(null);
        if (token == null || token.getUsedAt() != null) {
            return new AuthDtos.TokenValidity(false, ErrorCodes.INVALID_RESET_TOKEN, null, null, null);
        }
        if (clock.now().isAfter(token.getExpiresAt())) {
            return new AuthDtos.TokenValidity(false, ErrorCodes.RESET_TOKEN_EXPIRED, null, null, null);
        }
        return new AuthDtos.TokenValidity(true, null, null, null, null);
    }

    @Transactional
    public void reset(AuthDtos.ResetPasswordRequest req) {
        rateLimiter.check("reset:ip:" + ClientInfo.currentIp(), 20, Duration.ofHours(1));
        AccountToken token = tokens.findByTokenHashAndPurpose(TokenHasher.sha256Hex(req.token()), AccountTokenPurpose.PASSWORD_RESET)
                .orElseThrow(() -> ApiException.badRequest(ErrorCodes.INVALID_RESET_TOKEN,
                        "This password reset link is invalid or has already been used."));
        if (token.getUsedAt() != null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_RESET_TOKEN, "This password reset link has already been used.");
        }
        Instant now = clock.now();
        if (now.isAfter(token.getExpiresAt())) {
            throw ApiException.badRequest(ErrorCodes.RESET_TOKEN_EXPIRED, "This password reset link has expired. Please request a new one.");
        }
        User user = users.findById(token.getUserId()).orElseThrow();
        passwordPolicy.validate(req.newPassword(), req.confirmPassword(), user.getEmail(), "newPassword", "confirmPassword");
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.bumpTokenVersion();
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now); // the reset link proves ownership of the inbox
        }
        users.save(user);
        token.setUsedAt(now);
        tokens.save(token);
        refreshTokens.revokeAll(user.getId());
        principals.evict(user.getId());
        audit.recordAs(new AuditService.Actor(user.getId(), user.getRole().name(), user.getEmail()), AuditActions.PASSWORD_RESET,
                "USER", user.getId(), null, null);
        events.publishEvent(new DomainEvents.PasswordChanged(user.getId()));
    }
}
