package com.smartlib.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.ClientInfo;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.PrincipalService;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;

/** Self-service profile for every role. Users can never change their own role, status or library here. */
@RestController
@RequestMapping("/api/me")
public class ProfileController {

    private final UserRepository users;
    private final AuthService auth;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final RefreshTokenService refreshTokens;
    private final PrincipalService principals;
    private final AuditService audit;
    private final AuthCookies cookies;
    private final AppProperties properties;
    private final ApplicationEventPublisher events;

    public ProfileController(UserRepository users, AuthService auth, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                             RefreshTokenService refreshTokens, PrincipalService principals, AuditService audit, AuthCookies cookies,
                             AppProperties properties, ApplicationEventPublisher events) {
        this.users = users;
        this.auth = auth;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.refreshTokens = refreshTokens;
        this.principals = principals;
        this.audit = audit;
        this.cookies = cookies;
        this.properties = properties;
        this.events = events;
    }

    @GetMapping("/profile")
    public AuthDtos.Me profile() {
        return auth.me(CurrentUser.require().userId());
    }

    @PutMapping("/profile")
    @Transactional
    public AuthDtos.Me update(@Valid @RequestBody AuthDtos.UpdateProfileRequest req) {
        User user = users.findById(CurrentUser.require().userId()).orElseThrow();
        user.setFullName(req.fullName().trim());
        user.setPhone(Texts.trimToNull(req.phone()));
        users.save(user);
        principals.evict(user.getId());
        audit.record(AuditActions.PROFILE_UPDATED, "USER", user.getId(), null, null);
        return auth.toMe(principals.toPrincipal(user), user);
    }

    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<AuthDtos.LoginResponse> changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest req,
                                                                 HttpServletRequest request) {
        User user = users.findById(CurrentUser.require().userId()).orElseThrow();
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.validation("currentPassword", "Current password is incorrect")
                    .with("code2", ErrorCodes.WRONG_PASSWORD);
        }
        passwordPolicy.validate(req.newPassword(), req.confirmPassword(), user.getEmail(), "newPassword", "confirmPassword");
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.bumpTokenVersion();
        users.save(user);
        refreshTokens.revokeAll(user.getId());
        principals.evict(user.getId());
        audit.record(AuditActions.PASSWORD_CHANGED, "USER", user.getId(), null, null);
        events.publishEvent(new DomainEvents.PasswordChanged(user.getId()));
        AuthService.Session session = auth.newSession(user, ClientInfo.ip(request), ClientInfo.userAgent(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), refreshTokens.ttl()).toString())
                .body(new AuthDtos.LoginResponse(session.accessToken(), properties.jwt().accessTtl().toSeconds(), session.me()));
    }
}
