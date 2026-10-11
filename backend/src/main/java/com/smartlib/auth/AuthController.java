package com.smartlib.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.web.ClientInfo;
import com.smartlib.platform.PlatformSettings;
import com.smartlib.security.CurrentUser;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Required on cookie-authenticated endpoints: a simple cross-site form/request cannot set custom headers. */
    public static final String CSRF_HEADER = "X-Requested-With";
    public static final String CSRF_VALUE = "SmartLib";

    private final RegistrationService registration;
    private final VerificationService verification;
    private final AuthService auth;
    private final PasswordResetService passwordReset;
    private final InviteService invites;
    private final RefreshTokenService refreshTokens;
    private final AuthCookies cookies;
    private final AppProperties properties;
    private final PlatformSettings settings;

    public AuthController(RegistrationService registration, VerificationService verification, AuthService auth,
                          PasswordResetService passwordReset, InviteService invites, RefreshTokenService refreshTokens,
                          AuthCookies cookies, AppProperties properties, PlatformSettings settings) {
        this.registration = registration;
        this.verification = verification;
        this.auth = auth;
        this.passwordReset = passwordReset;
        this.invites = invites;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.properties = properties;
        this.settings = settings;
    }

    @GetMapping("/policy")
    public AuthDtos.AuthPolicy policy() {
        var v = properties.verification();
        return new AuthDtos.AuthPolicy(6, v.otpTtl().toSeconds(), v.linkTtl().toSeconds(), v.resendCooldown().toSeconds(),
                v.maxResendsPerDay(), v.maxAttempts(), PasswordPolicy.MIN_LENGTH, PasswordPolicy.MAX_BYTES,
                settings.isEnabled(PlatformSettings.LIBRARIAN_REGISTRATION_OPEN),
                settings.isEnabled(PlatformSettings.ADMIN_REGISTRATION_OPEN));
    }

    @PostMapping("/register/member")
    public ResponseEntity<AuthDtos.RegistrationResponse> registerMember(@Valid @RequestBody AuthDtos.RegisterMemberRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.registerMember(req));
    }

    @PostMapping("/register/librarian")
    public ResponseEntity<AuthDtos.RegistrationResponse> registerLibrarian(@Valid @RequestBody AuthDtos.RegisterLibrarianRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.registerLibrarian(req));
    }

    @PostMapping("/register/admin")
    public ResponseEntity<AuthDtos.RegistrationResponse> registerAdmin(@Valid @RequestBody AuthDtos.RegisterAdminRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registration.registerAdmin(req));
    }

    @PostMapping("/verify-email/otp")
    public AuthDtos.VerificationResult verifyOtp(@Valid @RequestBody AuthDtos.VerifyOtpRequest req) {
        return verification.verifyOtp(req.email(), req.otp());
    }

    @PostMapping("/verify-email/link")
    public AuthDtos.VerificationResult verifyLink(@Valid @RequestBody AuthDtos.VerifyLinkRequest req) {
        return verification.verifyLink(req.token());
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<AuthDtos.ResendResponse> resend(@Valid @RequestBody AuthDtos.EmailRequest req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(verification.resend(req.email()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req, HttpServletRequest request) {
        AuthService.Session session = auth.login(req, ClientInfo.ip(request), ClientInfo.userAgent(request));
        return withSession(session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.LoginResponse> refresh(@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String token,
                                                          @RequestHeader(name = CSRF_HEADER, required = false) String csrf,
                                                          HttpServletRequest request) {
        requireCsrfHeader(csrf);
        if (token == null || token.isBlank()) {
            // No session cookie at all: a normal anonymous visit, not an error.
            return ResponseEntity.noContent().build();
        }
        AuthService.Session session = auth.refresh(token, ClientInfo.ip(request), ClientInfo.userAgent(request));
        return withSession(session);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false) String token,
                                       @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        requireCsrfHeader(csrf);
        auth.logout(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll() {
        auth.logoutEverywhere(CurrentUser.require().userId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).build();
    }

    @GetMapping("/me")
    public AuthDtos.Me me() {
        return auth.me(CurrentUser.require().userId());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<AuthDtos.MessageResponse> forgot(@Valid @RequestBody AuthDtos.EmailRequest req) {
        passwordReset.requestReset(req.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AuthDtos.MessageResponse(PasswordResetService.GENERIC_MESSAGE));
    }

    @PostMapping("/reset-password/validate")
    public AuthDtos.TokenValidity validateReset(@Valid @RequestBody AuthDtos.TokenRequest req) {
        return passwordReset.validate(req.token());
    }

    @PostMapping("/reset-password")
    public AuthDtos.MessageResponse reset(@Valid @RequestBody AuthDtos.ResetPasswordRequest req) {
        passwordReset.reset(req);
        return new AuthDtos.MessageResponse("Your password has been reset. Please log in with your new password.");
    }

    @PostMapping("/invite/validate")
    public AuthDtos.TokenValidity validateInvite(@Valid @RequestBody AuthDtos.TokenRequest req) {
        return invites.validate(req.token());
    }

    @PostMapping("/accept-invite")
    public AuthDtos.MessageResponse acceptInvite(@Valid @RequestBody AuthDtos.ResetPasswordRequest req) {
        invites.accept(req.token(), req.newPassword(), req.confirmPassword());
        return new AuthDtos.MessageResponse("Your administrator account is ready. Please log in.");
    }

    private ResponseEntity<AuthDtos.LoginResponse> withSession(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(session.refreshToken(), refreshTokens.ttl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthDtos.LoginResponse(session.accessToken(), properties.jwt().accessTtl().toSeconds(), session.me()));
    }

    private static void requireCsrfHeader(String value) {
        if (!CSRF_VALUE.equals(value)) {
            throw ApiException.forbidden(ErrorCodes.CSRF_CHECK_FAILED, "Missing required request header.");
        }
    }
}
