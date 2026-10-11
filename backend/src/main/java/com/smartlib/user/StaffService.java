package com.smartlib.user;

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
import com.smartlib.auth.RefreshTokenService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.security.LibraryScope;
import com.smartlib.security.PrincipalService;

/** Librarian applications, staff lists and library member management — always scoped to one library. */
@Service
public class StaffService {

    private final StaffAssignmentRepository staff;
    private final UserRepository users;
    private final LibraryMembershipRepository memberships;
    private final NamedParameterJdbcTemplate jdbc;
    private final PrincipalService principals;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppClock clock;

    public StaffService(StaffAssignmentRepository staff, UserRepository users, LibraryMembershipRepository memberships,
                        NamedParameterJdbcTemplate jdbc, PrincipalService principals, RefreshTokenService refreshTokens,
                        AuditService audit, ApplicationEventPublisher events, AppClock clock) {
        this.staff = staff;
        this.users = users;
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.principals = principals;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record Application(long id, long userId, String fullName, String email, String phone, boolean emailVerified,
                              String role, ApprovalStatus status, String employeeRef, String applicationNote, Instant appliedAt,
                              Instant reviewedAt, String reviewedByName, String rejectionReason) {
    }

    public record MemberRow(long userId, String fullName, String email, String phone, MembershipStatus membershipStatus,
                            Instant joinedAt, long activeLoans, long overdueLoans, long unpaidFinesPaise, String accountStatus) {
    }

    public PageResponse<Application> applications(LibraryScope scope, String status, String role, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE s.library_id = :lib");
        MapSqlParameterSource p = new MapSqlParameterSource("lib", scope.libraryId());
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            if ("UNVERIFIED".equalsIgnoreCase(status)) {
                where.append(" AND u.email_verified_at IS NULL");
            } else {
                where.append(" AND s.approval_status = :status");
                p.addValue("status", status.toUpperCase());
            }
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND u.role = :role");
            p.addValue("role", role.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        String from = " FROM staff_assignments s JOIN users u ON u.id = s.user_id LEFT JOIN users r ON r.id = s.reviewed_by";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Application> rows = jdbc.query("SELECT s.*, u.full_name, u.email, u.phone, u.role, u.email_verified_at, r.full_name AS reviewer"
                        + from + where + " ORDER BY (s.approval_status = 'PENDING') DESC, s.created_at DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new Application(rs.getLong("id"), rs.getLong("user_id"), rs.getString("full_name"), rs.getString("email"),
                        rs.getString("phone"), rs.getTimestamp("email_verified_at") != null, rs.getString("role"),
                        ApprovalStatus.valueOf(rs.getString("approval_status")), rs.getString("employee_ref"),
                        rs.getString("application_note"), ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("reviewed_at")),
                        rs.getString("reviewer"), rs.getString("rejection_reason")));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public void approve(LibraryScope scope, long assignmentId) {
        scope.requireAdmin();
        StaffAssignment a = librarianApplication(scope, assignmentId);
        User user = users.findById(a.getUserId()).orElseThrow();
        if (a.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending applications can be approved.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.conflict(ErrorCodes.EMAIL_NOT_VERIFIED,
                    "The applicant has not verified their email yet. Approval is possible after email verification.");
        }
        a.setApprovalStatus(ApprovalStatus.APPROVED);
        a.setReviewedBy(scope.actorId());
        a.setReviewedAt(clock.now());
        a.setRejectionReason(null);
        staff.save(a);
        principals.evict(user.getId());
        audit.record(AuditActions.LIBRARIAN_APPROVED, "USER", user.getId(), scope.libraryId(), AuditService.details("email", user.getEmail()));
        events.publishEvent(new DomainEvents.LibrarianReviewed(user.getId(), scope.libraryId(), true, null));
    }

    @Transactional
    public void reject(LibraryScope scope, long assignmentId, String reason) {
        scope.requireAdmin();
        StaffAssignment a = librarianApplication(scope, assignmentId);
        if (a.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending applications can be rejected.");
        }
        a.setApprovalStatus(ApprovalStatus.REJECTED);
        a.setReviewedBy(scope.actorId());
        a.setReviewedAt(clock.now());
        a.setRejectionReason(reason.trim());
        staff.save(a);
        principals.evict(a.getUserId());
        audit.record(AuditActions.LIBRARIAN_REJECTED, "USER", a.getUserId(), scope.libraryId(), AuditService.details("reason", reason));
        events.publishEvent(new DomainEvents.LibrarianReviewed(a.getUserId(), scope.libraryId(), false, reason.trim()));
    }

    /**
     * Library-scoped approvals cover LIBRARIAN applications only. ADMIN applications for this library are decided by a
     * SUPER_ADMIN on the platform, so a library admin cannot approve another admin.
     */
    private StaffAssignment librarianApplication(LibraryScope scope, long assignmentId) {
        return staff.findByIdAndLibraryId(assignmentId, scope.libraryId())
                .filter(a -> users.findById(a.getUserId()).map(u -> u.getRole() == UserRole.LIBRARIAN).orElse(false))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Application not found."));
    }

    @Transactional
    public void revoke(LibraryScope scope, long userId) {
        scope.requireAdmin();
        StaffAssignment a = staff.findByUserId(userId).filter(s -> Long.valueOf(scope.libraryId()).equals(s.getLibraryId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Staff member not found."));
        User user = users.findById(userId).orElseThrow();
        if (user.getRole() == UserRole.ADMIN && !scope.principal().isSuperAdmin()) {
            throw ApiException.forbidden("Only a platform super administrator can revoke a library administrator.");
        }
        if (userId == scope.actorId()) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "You cannot revoke your own access.");
        }
        if (a.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only active staff can be revoked.");
        }
        a.setApprovalStatus(ApprovalStatus.REVOKED);
        a.setReviewedBy(scope.actorId());
        a.setReviewedAt(clock.now());
        staff.save(a);
        user.bumpTokenVersion();
        users.save(user);
        refreshTokens.revokeAll(userId);
        principals.evict(userId);
        audit.record(AuditActions.STAFF_REVOKED, "USER", userId, scope.libraryId(), AuditService.details("email", user.getEmail()));
    }

    public PageResponse<MemberRow> members(LibraryScope scope, String q, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE m.library_id = :lib");
        MapSqlParameterSource p = new MapSqlParameterSource("lib", scope.libraryId());
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND m.status = :status");
            p.addValue("status", status.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q OR u.phone LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM library_memberships m JOIN users u ON u.id = m.user_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        p.addValue("today", java.sql.Date.valueOf(clock.today(com.smartlib.common.time.AppClock.DEFAULT_ZONE)));
        List<MemberRow> rows = jdbc.query("""
                SELECT u.id, u.full_name, u.email, u.phone, u.status AS account_status, m.status, m.joined_at,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.member_id = u.id AND b.library_id = m.library_id AND b.status = 'ACTIVE') AS active_loans,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.member_id = u.id AND b.library_id = m.library_id AND b.status = 'ACTIVE' AND b.due_date < :today) AS overdue_loans,
                  (SELECT COALESCE(SUM(f.amount_paise),0) FROM fines f WHERE f.member_id = u.id AND f.library_id = m.library_id AND f.status = 'UNPAID') AS unpaid
                FROM library_memberships m JOIN users u ON u.id = m.user_id""" + where
                        + " ORDER BY m.joined_at DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new MemberRow(rs.getLong("id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                        MembershipStatus.valueOf(rs.getString("status")), ts(rs.getTimestamp("joined_at")), rs.getLong("active_loans"),
                        rs.getLong("overdue_loans"), rs.getLong("unpaid"), rs.getString("account_status")));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional
    public void setMembershipStatus(LibraryScope scope, long userId, MembershipStatus status) {
        scope.requireAdmin();
        LibraryMembership m = memberships.findByUserIdAndLibraryId(userId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Member not found in this library."));
        m.setStatus(status);
        memberships.save(m);
        audit.record(AuditActions.MEMBERSHIP_STATUS_CHANGED, "USER", userId, scope.libraryId(), AuditService.details("status", status.name()));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
