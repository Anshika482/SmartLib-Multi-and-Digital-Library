package com.smartlib.platform;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.PrincipalService;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;

/**
 * SUPER_ADMIN review of self-registered ADMIN and SUPER_ADMIN applications. Librarian applications stay with the
 * library's own administrators ({@code StaffService}).
 */
@Service
public class PlatformApplicationService {

    public record AdminApplication(long id, long userId, String fullName, String email, String phone, boolean emailVerified,
                                   String role, Long libraryId, String libraryName, String designation, String employeeRef,
                                   String applicationNote, ApprovalStatus status, Instant appliedAt, Instant reviewedAt,
                                   String reviewedByName, String rejectionReason) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final StaffAssignmentRepository staff;
    private final UserRepository users;
    private final LibraryRepository libraries;
    private final PrincipalService principals;
    private final AuditService audit;
    private final AppClock clock;
    private final ApplicationEventPublisher events;
    private final com.smartlib.security.SuperAdminPolicy superAdmins;

    public PlatformApplicationService(NamedParameterJdbcTemplate jdbc, StaffAssignmentRepository staff, UserRepository users,
                                      LibraryRepository libraries, PrincipalService principals, AuditService audit, AppClock clock,
                                      ApplicationEventPublisher events, com.smartlib.security.SuperAdminPolicy superAdmins) {
        this.superAdmins = superAdmins;
        this.jdbc = jdbc;
        this.staff = staff;
        this.users = users;
        this.libraries = libraries;
        this.principals = principals;
        this.audit = audit;
        this.clock = clock;
        this.events = events;
    }

    /**
     * @param role   ADMIN, SUPER_ADMIN or ALL
     * @param status PENDING, UNVERIFIED (email not verified yet), APPROVED, REJECTED or ALL
     */
    public PageResponse<AdminApplication> list(String role, String status, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE s.origin = :origin AND u.role IN ('ADMIN', 'SUPER_ADMIN')");
        MapSqlParameterSource p = new MapSqlParameterSource("origin", StaffAssignment.ORIGIN_APPLICATION);
        if ("ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role)) {
            where.append(" AND u.role = :role");
            p.addValue("role", role.toUpperCase());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            if ("UNVERIFIED".equalsIgnoreCase(status)) {
                where.append(" AND s.approval_status = 'PENDING' AND u.email_verified_at IS NULL");
            } else {
                ApprovalStatus parsed = java.util.Arrays.stream(ApprovalStatus.values())
                        .filter(v -> v.name().equalsIgnoreCase(status)).findFirst()
                        .orElseThrow(() -> ApiException.validation("status", "Unknown application status"));
                where.append(" AND s.approval_status = :status");
                p.addValue("status", parsed.name());
            }
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        String from = " FROM staff_assignments s JOIN users u ON u.id = s.user_id"
                + " LEFT JOIN libraries l ON l.id = s.library_id LEFT JOIN users r ON r.id = s.reviewed_by";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<AdminApplication> rows = jdbc.query("SELECT s.*, u.full_name, u.email, u.phone, u.role, u.email_verified_at,"
                        + " l.name AS library_name, r.full_name AS reviewer" + from + where
                        + " ORDER BY (s.approval_status = 'PENDING') DESC, s.created_at DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    long lib = rs.getLong("library_id");
                    Long libraryId = rs.wasNull() ? null : lib;
                    return new AdminApplication(rs.getLong("id"), rs.getLong("user_id"), rs.getString("full_name"),
                            rs.getString("email"), rs.getString("phone"), rs.getTimestamp("email_verified_at") != null,
                            rs.getString("role"), libraryId, rs.getString("library_name"), rs.getString("designation"),
                            rs.getString("employee_ref"), rs.getString("application_note"),
                            ApprovalStatus.valueOf(rs.getString("approval_status")), ts(rs.getTimestamp("created_at")),
                            ts(rs.getTimestamp("reviewed_at")), rs.getString("reviewer"), rs.getString("rejection_reason"));
                });
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public void approve(long assignmentId, long reviewerId) {
        StaffAssignment a = pendingApplication(assignmentId, "approved");
        User user = users.findById(a.getUserId()).orElseThrow();
        if (user.getRole() == UserRole.SUPER_ADMIN && superAdmins.singleSuperAdmin()) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "SmartLib has a single Super Admin (the account configured in .env). Super Admin applications cannot be approved.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.conflict(ErrorCodes.EMAIL_NOT_VERIFIED,
                    "The applicant has not verified their email yet. Approval is possible after email verification.");
        }
        if (user.getRole() == UserRole.ADMIN) {
            Library library = libraries.findById(a.getLibraryId()).orElseThrow();
            if (!library.isActive()) {
                throw ApiException.conflict(ErrorCodes.INVALID_STATE, library.getName() + " is inactive. Reactivate it before approving an admin.");
            }
        }
        a.setApprovalStatus(ApprovalStatus.APPROVED);
        a.setReviewedBy(reviewerId);
        a.setReviewedAt(clock.now());
        a.setRejectionReason(null);
        staff.save(a);
        principals.evict(user.getId());
        boolean superAdmin = user.getRole() == UserRole.SUPER_ADMIN;
        audit.record(superAdmin ? AuditActions.SUPER_ADMIN_APPROVED : AuditActions.ADMIN_APPROVED, "USER", user.getId(),
                a.getLibraryId(), AuditService.details("email", user.getEmail()));
        events.publishEvent(new DomainEvents.AdminReviewed(user.getId(), a.getLibraryId(), user.getRole().name(), true, null));
    }

    @Transactional
    public void reject(long assignmentId, String reason, long reviewerId) {
        StaffAssignment a = pendingApplication(assignmentId, "rejected");
        User user = users.findById(a.getUserId()).orElseThrow();
        String why = reason.trim();
        a.setApprovalStatus(ApprovalStatus.REJECTED);
        a.setReviewedBy(reviewerId);
        a.setReviewedAt(clock.now());
        a.setRejectionReason(why);
        staff.save(a);
        principals.evict(user.getId());
        boolean superAdmin = user.getRole() == UserRole.SUPER_ADMIN;
        audit.record(superAdmin ? AuditActions.SUPER_ADMIN_REJECTED : AuditActions.ADMIN_REJECTED, "USER", user.getId(),
                a.getLibraryId(), AuditService.details("reason", why));
        events.publishEvent(new DomainEvents.AdminReviewed(user.getId(), a.getLibraryId(), user.getRole().name(), false, why));
    }

    /** A self-registered ADMIN / SUPER_ADMIN application that is still PENDING; anything else is 404 or 409. */
    private StaffAssignment pendingApplication(long assignmentId, String verb) {
        StaffAssignment a = staff.findById(assignmentId)
                .filter(s -> StaffAssignment.ORIGIN_APPLICATION.equals(s.getOrigin()))
                .filter(s -> users.findById(s.getUserId())
                        .map(u -> u.getRole() == UserRole.ADMIN || u.getRole() == UserRole.SUPER_ADMIN).orElse(false))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Application not found."));
        if (a.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending applications can be " + verb + ".");
        }
        return a;
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
