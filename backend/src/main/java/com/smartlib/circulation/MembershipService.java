package com.smartlib.circulation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.user.LibraryMembership;
import com.smartlib.user.LibraryMembershipRepository;
import com.smartlib.user.MembershipStatus;

/** Members join a library explicitly or automatically on their first request there. Admins can suspend a membership. */
@Service
public class MembershipService {

    private final LibraryMembershipRepository memberships;
    private final LibraryRepository libraries;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MembershipService(LibraryMembershipRepository memberships, LibraryRepository libraries, JdbcTemplate jdbc, AuditService audit) {
        this.memberships = memberships;
        this.libraries = libraries;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Returns an ACTIVE membership, creating it if missing; throws if the member is suspended at this library. */
    @Transactional
    public LibraryMembership ensureActive(long userId, long libraryId) {
        Timestamp now = Timestamp.from(Instant.now());
        int inserted = jdbc.update("INSERT IGNORE INTO library_memberships (user_id, library_id, status, joined_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)",
                userId, libraryId, now, now);
        LibraryMembership m = memberships.findByUserIdAndLibraryId(userId, libraryId).orElseThrow();
        if (inserted > 0) {
            audit.record(AuditActions.MEMBERSHIP_CREATED, "USER", userId, libraryId, null);
        }
        if (m.getStatus() == MembershipStatus.SUSPENDED) {
            throw ApiException.forbidden(ErrorCodes.MEMBERSHIP_SUSPENDED,
                    "Your membership at this library is suspended. Please contact the library.");
        }
        return m;
    }

    @Transactional
    public CirculationDtos.MembershipView join(long userId, long libraryId) {
        Library library = libraries.findById(libraryId).filter(Library::isActive)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found."));
        LibraryMembership m = ensureActive(userId, libraryId);
        return new CirculationDtos.MembershipView(library.getId(), library.getName(), library.getSlug(), m.getStatus().name(), m.getJoinedAt());
    }

    public List<CirculationDtos.MembershipView> mine(long userId) {
        return jdbc.query("""
                SELECT m.library_id, l.name, l.slug, m.status, m.joined_at FROM library_memberships m
                JOIN libraries l ON l.id = m.library_id WHERE m.user_id = ? ORDER BY l.name""",
                (rs, i) -> new CirculationDtos.MembershipView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()), userId);
    }
}
