package com.smartlib.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.auth.InviteService;
import com.smartlib.auth.RefreshTokenService;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.library.LibraryAdminService;
import com.smartlib.library.LibraryStatus;
import com.smartlib.report.ReportService;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.PrincipalService;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/** SUPER_ADMIN-only platform administration (the explicit exception to library isolation). */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final LibraryAdminService libraries;
    private final ReportService reports;
    private final InviteService invites;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final PrincipalService principals;
    private final RefreshTokenService refreshTokens;
    private final PlatformSettings settings;
    private final AuditService audit;
    private final AppProperties properties;

    private final com.smartlib.security.SuperAdminPolicy superAdmins;

    public PlatformController(LibraryAdminService libraries, ReportService reports, InviteService invites, UserRepository users,
                              NamedParameterJdbcTemplate jdbc, PrincipalService principals, RefreshTokenService refreshTokens,
                              PlatformSettings settings, AuditService audit, AppProperties properties,
                              com.smartlib.security.SuperAdminPolicy superAdmins) {
        this.superAdmins = superAdmins;
        this.libraries = libraries;
        this.reports = reports;
        this.invites = invites;
        this.users = users;
        this.jdbc = jdbc;
        this.principals = principals;
        this.refreshTokens = refreshTokens;
        this.settings = settings;
        this.audit = audit;
        this.properties = properties;
    }

    public record StatusRequest(@NotNull String status) {
    }

    public record InviteAdminRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 2, max = 120) String fullName) {
    }

    public record UserRow(long id, String fullName, String email, String phone, String role, String status, boolean emailVerified,
                          Long libraryId, String libraryName, String approvalStatus, Instant createdAt, Instant lastLoginAt) {
    }

    // ----- libraries -----

    @GetMapping("/libraries")
    public List<ReportService.LibraryRow> libraries() {
        return reports.libraryComparison();
    }

    @GetMapping("/libraries/{id}")
    public Map<String, Object> library(@PathVariable long id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("library", libraries.view(id));
        out.put("summary", reports.librarySummary(id));
        out.put("staff", users(null, null, null, id, 0, 100).content());
        return out;
    }

    @PostMapping("/libraries")
    public ResponseEntity<LibraryAdminService.LibraryAdminView> create(@Valid @RequestBody LibraryAdminService.CreateLibraryInput in) {
        return ResponseEntity.status(HttpStatus.CREATED).body(libraries.create(in));
    }

    @PutMapping("/libraries/{id}")
    public LibraryAdminService.LibraryAdminView update(@PathVariable long id, @Valid @RequestBody LibraryAdminService.ProfileInput in) {
        libraries.view(id);
        return libraries.updateProfile(id, in);
    }

    @PatchMapping("/libraries/{id}/status")
    public LibraryAdminService.LibraryAdminView status(@PathVariable long id, @Valid @RequestBody StatusRequest req) {
        return libraries.setStatus(id, parse(LibraryStatus.class, req.status()));
    }

    @PostMapping("/libraries/{id}/admins")
    public ResponseEntity<Map<String, Long>> inviteAdmin(@PathVariable long id, @Valid @RequestBody InviteAdminRequest req) {
        long userId = invites.inviteAdmin(req.email(), req.fullName(), id, CurrentUser.require().userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", userId));
    }

    // ----- users -----

    @GetMapping("/users")
    public PageResponse<UserRow> listUsers(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
                                           @RequestParam(required = false) String status, @RequestParam(required = false) Long libraryId,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return users(q, role, status, libraryId, PageResponse.clampPage(page), PageResponse.clampSize(size, 25, 100));
    }

    @PatchMapping("/users/{id}/status")
    @Transactional
    public UserRow setUserStatus(@PathVariable long id, @Valid @RequestBody StatusRequest req) {
        UserStatus status = parse(UserStatus.class, req.status());
        if (status == UserStatus.INVITED) {
            throw ApiException.validation("status", "Status must be ACTIVE, SUSPENDED or DISABLED");
        }
        if (id == CurrentUser.require().userId()) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "You cannot change your own account status.");
        }
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found."));
        if (u.getRole() == UserRole.SUPER_ADMIN && status == UserStatus.ACTIVE && !superAdmins.allows(u.getEmail())) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "SmartLib has a single Super Admin (the account configured in .env). Other Super Admin accounts stay disabled.");
        }
        u.setStatus(status);
        if (status != UserStatus.ACTIVE) {
            u.bumpTokenVersion();
            refreshTokens.revokeAll(id);
        }
        users.save(u);
        principals.evict(id);
        audit.record(AuditActions.USER_STATUS_CHANGED, "USER", id, null, AuditService.details("status", status.name(), "email", u.getEmail()));
        return users(null, null, null, null, 0, 1, id).content().get(0);
    }

    // ----- configuration -----

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings.all());
        Map<String, Object> integrations = new LinkedHashMap<>();
        integrations.put("geminiConfigured", properties.gemini().configured());
        integrations.put("geminiModel", properties.gemini().model());
        integrations.put("razorpayConfigured", properties.razorpay().configured());
        integrations.put("razorpayMode", properties.razorpay().configured() && properties.razorpay().keyId().startsWith("rzp_test") ? "test" : "live/none");
        integrations.put("paymentsDemo", !properties.razorpay().configured() && properties.razorpay().demoMode());
        integrations.put("coverLookupEnabled", properties.covers().enabled());
        out.put("integrations", integrations);
        return out;
    }

    @PutMapping("/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> changes) {
        long actor = CurrentUser.require().userId();
        changes.forEach((k, v) -> settings.set(k, v, actor));
        audit.record(AuditActions.SETTINGS_UPDATED, "PLATFORM", "settings", null, AuditService.details("keys", String.join(",", changes.keySet())));
        return config();
    }

    // ----- helpers -----

    private PageResponse<UserRow> users(String q, String role, String status, Long libraryId, int page, int size) {
        return users(q, role, status, libraryId, page, size, null);
    }

    private PageResponse<UserRow> users(String q, String role, String status, Long libraryId, int page, int size, Long id) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (id != null) {
            where.append(" AND u.id = :id");
            p.addValue("id", id);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        if (role != null && !role.isBlank() && !"ALL".equalsIgnoreCase(role)) {
            where.append(" AND u.role = :role");
            p.addValue("role", parse(UserRole.class, role).name());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND u.status = :status");
            p.addValue("status", parse(UserStatus.class, status).name());
        }
        if (libraryId != null) {
            where.append(" AND s.library_id = :lib");
            p.addValue("lib", libraryId);
        }
        String from = " FROM users u LEFT JOIN staff_assignments s ON s.user_id = u.id LEFT JOIN libraries l ON l.id = s.library_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<UserRow> rows = jdbc.query("SELECT u.*, s.library_id, s.approval_status, l.name AS lib_name" + from + where
                        + " ORDER BY u.created_at DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    Timestamp last = rs.getTimestamp("last_login_at");
                    return new UserRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                            rs.getString("role"), rs.getString("status"), rs.getTimestamp("email_verified_at") != null,
                            (Long) rs.getObject("library_id", Long.class), rs.getString("lib_name"), rs.getString("approval_status"),
                            rs.getTimestamp("created_at").toInstant(), last == null ? null : last.toInstant());
                });
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw ApiException.validation("status", "Invalid value: " + value);
        }
    }
}
