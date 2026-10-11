package com.smartlib.fine;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.catalogue.CatalogueQueryService;
import com.smartlib.circulation.Borrowing;
import com.smartlib.circulation.BorrowingRepository;
import com.smartlib.circulation.CirculationDtos.LibraryRef;
import com.smartlib.circulation.CirculationDtos.MemberRef;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineDtos.FineView;
import com.smartlib.library.LibraryPolicy;
import com.smartlib.security.LibraryScope;
import com.smartlib.user.LibraryMembershipRepository;

@Service
public class FineService {

    private final FineRepository fines;
    private final BorrowingRepository borrowings;
    private final LibraryMembershipRepository memberships;
    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogueQueryService catalogue;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppClock clock;

    public FineService(FineRepository fines, BorrowingRepository borrowings, LibraryMembershipRepository memberships,
                       NamedParameterJdbcTemplate jdbc, CatalogueQueryService catalogue, AuditService audit,
                       ApplicationEventPublisher events, AppClock clock) {
        this.fines = fines;
        this.borrowings = borrowings;
        this.memberships = memberships;
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    /** Creates the overdue fine for a closed loan, if any is due. Idempotent per loan (unique borrowing+type). */
    @Transactional
    public Fine createOverdueFine(Borrowing b, LibraryPolicy policy, LocalDate returnDate, Long actorId) {
        long days = FineCalculator.overdueDays(b.getDueDate(), returnDate);
        long amount = FineCalculator.amount(b.getDueDate(), returnDate, policy.getGraceDays(), policy.getFinePerDayPaise(),
                policy.getMaxFinePerLoanPaise());
        if (amount <= 0) {
            return null;
        }
        Fine f = new Fine();
        f.setMemberId(b.getMemberId());
        f.setLibraryId(b.getLibraryId());
        f.setBorrowingId(b.getId());
        f.setType(FineType.OVERDUE);
        f.setAmountPaise(amount);
        f.setOverdueDays((int) days);
        f.setStatus(FineStatus.UNPAID);
        f.setReason("Returned " + days + (days == 1 ? " day" : " days") + " after the due date (" + b.getDueDate() + ")");
        f.setCreatedBy(actorId);
        fines.saveAndFlush(f);
        audit.record(AuditActions.FINE_CREATED, "FINE", f.getId(), f.getLibraryId(),
                AuditService.details("type", "OVERDUE", "amountPaise", amount, "memberId", f.getMemberId()));
        events.publishEvent(new DomainEvents.FineCreated(f.getId()));
        return f;
    }

    @Transactional
    public Fine createForBorrowing(Borrowing b, FineType type, long amountPaise, String reason, Long actorId) {
        Fine f = new Fine();
        f.setMemberId(b.getMemberId());
        f.setLibraryId(b.getLibraryId());
        f.setBorrowingId(b.getId());
        f.setType(type);
        f.setAmountPaise(amountPaise);
        f.setStatus(FineStatus.UNPAID);
        f.setReason(reason);
        f.setCreatedBy(actorId);
        fines.saveAndFlush(f);
        audit.record(AuditActions.FINE_CREATED, "FINE", f.getId(), f.getLibraryId(),
                AuditService.details("type", type.name(), "amountPaise", amountPaise, "memberId", f.getMemberId()));
        events.publishEvent(new DomainEvents.FineCreated(f.getId()));
        return f;
    }

    @Transactional
    public FineView createManual(LibraryScope scope, FineDtos.CreateFineRequest req) {
        if (req.type() == FineType.OVERDUE) {
            throw ApiException.validation("type", "Overdue fines are calculated automatically on return");
        }
        memberships.findByUserIdAndLibraryId(req.memberId(), scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Member not found in this library."));
        if (req.borrowingId() != null) {
            Borrowing b = borrowings.findByIdAndLibraryId(req.borrowingId(), scope.libraryId())
                    .filter(x -> x.getMemberId().equals(req.memberId()))
                    .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Loan not found for this member."));
            boolean exists = jdbc.queryForObject("SELECT COUNT(*) FROM fines WHERE borrowing_id = :b AND type = :t",
                    new MapSqlParameterSource("b", b.getId()).addValue("t", req.type().name()), Long.class) > 0;
            if (exists) {
                throw ApiException.conflict(ErrorCodes.CONFLICT, "A " + req.type().name().toLowerCase() + " fine already exists for this loan.");
            }
        }
        Fine f = new Fine();
        f.setMemberId(req.memberId());
        f.setLibraryId(scope.libraryId());
        f.setBorrowingId(req.borrowingId());
        f.setType(req.type());
        f.setAmountPaise(req.amountPaise());
        f.setStatus(FineStatus.UNPAID);
        f.setReason(req.reason().trim());
        f.setCreatedBy(scope.actorId());
        fines.saveAndFlush(f);
        audit.record(AuditActions.FINE_CREATED, "FINE", f.getId(), scope.libraryId(),
                AuditService.details("type", req.type().name(), "amountPaise", req.amountPaise(), "memberId", req.memberId()));
        events.publishEvent(new DomainEvents.FineCreated(f.getId()));
        return fine(f.getId());
    }

    @Transactional
    public FineView waive(LibraryScope scope, long fineId, String reason) {
        scope.requireAdmin();
        Fine f = fines.lockByIdAndLibraryId(fineId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Fine not found."));
        if (f.getStatus() != FineStatus.UNPAID) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only unpaid fines can be waived.");
        }
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE fine_id = :f AND status = 'CREATED' AND created_at > :since",
                new MapSqlParameterSource("f", fineId).addValue("since", Timestamp.from(clock.now().minusSeconds(1800))), Long.class);
        if (pending != null && pending > 0) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "A payment for this fine is in progress. Try again in a few minutes.");
        }
        f.setStatus(FineStatus.WAIVED);
        f.setWaivedBy(scope.actorId());
        f.setWaivedReason(reason.trim());
        fines.saveAndFlush(f);
        audit.record(AuditActions.FINE_WAIVED, "FINE", fineId, scope.libraryId(), AuditService.details("reason", reason));
        events.publishEvent(new DomainEvents.FineWaived(fineId));
        return fine(fineId);
    }

    // ---------- read models ----------

    private static final String SELECT = """
            SELECT f.*, l.name AS lib_name, l.slug AS lib_slug, bo.book_id, bo.due_date, bo.returned_at AS bo_returned_at,
                   u.full_name, u.email, u.phone,
                   (SELECT pa.id FROM payments pa WHERE pa.fine_id = f.id AND pa.status = 'PAID' ORDER BY pa.id DESC LIMIT 1) AS payment_id
            FROM fines f
            JOIN libraries l ON l.id = f.library_id
            JOIN users u ON u.id = f.member_id
            LEFT JOIN borrowings bo ON bo.id = f.borrowing_id""";

    public List<FineView> memberFines(long memberId, String status) {
        MapSqlParameterSource p = new MapSqlParameterSource("m", memberId);
        String where = " WHERE f.member_id = :m" + statusClause(status, p);
        return query(where + " ORDER BY (f.status = 'UNPAID') DESC, f.created_at DESC LIMIT 200", p, false);
    }

    public FineDtos.FineSummary memberSummary(long memberId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(status = 'UNPAID'), 0) AS unpaid_count,
                       COALESCE(SUM(CASE WHEN status = 'UNPAID' THEN amount_paise END), 0) AS unpaid,
                       COALESCE(SUM(CASE WHEN status = 'PAID' THEN amount_paise END), 0) AS paid,
                       COALESCE(SUM(CASE WHEN status = 'WAIVED' THEN amount_paise END), 0) AS waived
                FROM fines WHERE member_id = :m""", new MapSqlParameterSource("m", memberId),
                (rs, i) -> new FineDtos.FineSummary(rs.getLong("unpaid_count"), rs.getLong("unpaid"), rs.getLong("paid"), rs.getLong("waived")));
    }

    public PageResponse<FineView> libraryFines(LibraryScope scope, String status, String q, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("lib", scope.libraryId());
        StringBuilder where = new StringBuilder(" WHERE f.library_id = :lib").append(statusClause(status, p));
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM fines f JOIN users u ON u.id = f.member_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<FineView> rows = query(where + " ORDER BY (f.status = 'UNPAID') DESC, f.created_at DESC LIMIT :limit OFFSET :offset", p, true);
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public FineView fine(long id) {
        return query(" WHERE f.id = :id", new MapSqlParameterSource("id", id), true).get(0);
    }

    private static String statusClause(String status, MapSqlParameterSource p) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return "";
        }
        p.addValue("status", FineStatus.valueOf(status.toUpperCase()).name());
        return " AND f.status = :status";
    }

    private List<FineView> query(String where, MapSqlParameterSource p, boolean staffView) {
        List<Object[]> rows = jdbc.query(SELECT + where, p, (rs, i) -> row(rs));
        Map<Long, BookSummary> books = catalogue.summaries(rows.stream().map(r -> (Long) r[1]).filter(java.util.Objects::nonNull).toList());
        List<FineView> out = new ArrayList<>();
        for (Object[] r : rows) {
            FineView v = (FineView) r[0];
            out.add(new FineView(v.id(), v.type(), v.status(), v.amountPaise(), v.overdueDays(), v.reason(), v.createdAt(), v.paidAt(),
                    v.library(), r[1] == null ? null : books.get((Long) r[1]), v.borrowingId(), v.dueDate(), v.returnedAt(),
                    v.waivedReason(), v.paymentId(), staffView ? v.member() : null));
        }
        return out;
    }

    private static Object[] row(ResultSet rs) throws SQLException {
        Date due = rs.getDate("due_date");
        Timestamp ret = rs.getTimestamp("bo_returned_at");
        Timestamp paid = rs.getTimestamp("paid_at");
        int od = rs.getInt("overdue_days");
        Integer overdue = rs.wasNull() ? null : od;
        Long bookId = (Long) rs.getObject("book_id", Long.class);
        FineView v = new FineView(rs.getLong("id"), FineType.valueOf(rs.getString("type")), FineStatus.valueOf(rs.getString("status")),
                rs.getLong("amount_paise"), overdue, rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
                paid == null ? null : paid.toInstant(), new LibraryRef(rs.getLong("library_id"), rs.getString("lib_name"), rs.getString("lib_slug")),
                null, (Long) rs.getObject("borrowing_id", Long.class), due == null ? null : due.toLocalDate(),
                ret == null ? null : ret.toInstant(), rs.getString("waived_reason"), (Long) rs.getObject("payment_id", Long.class),
                new MemberRef(rs.getLong("member_id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone")));
        return new Object[]{v, bookId};
    }

    public Instant now() {
        return clock.now();
    }
}
