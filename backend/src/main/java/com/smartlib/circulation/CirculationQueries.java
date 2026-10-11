package com.smartlib.circulation;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.catalogue.CatalogueQueryService;
import com.smartlib.circulation.CirculationDtos.LibraryRef;
import com.smartlib.circulation.CirculationDtos.LoanView;
import com.smartlib.circulation.CirculationDtos.MemberRef;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineCalculator;

/** Read models for requests and loans. Callers always pass the member id or the scoped library id. */
@Service
public class CirculationQueries {

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogueQueryService catalogue;
    private final AppClock clock;

    public CirculationQueries(NamedParameterJdbcTemplate jdbc, CatalogueQueryService catalogue, AppClock clock) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    private static final String REQUEST_SELECT = """
            SELECT r.id, r.status, r.book_id, r.library_id, l.name AS lib_name, l.slug AS lib_slug, r.created_at, r.decided_at,
                   r.decision_reason, r.pickup_deadline, r.member_note, c.accession_no,
                   (SELECT bo.id FROM borrowings bo WHERE bo.request_id = r.id) AS borrowing_id,
                   u.id AS uid, u.full_name, u.email, u.phone,
                   (SELECT COUNT(*) FROM book_copies bc WHERE bc.holding_id = r.holding_id AND bc.status = 'AVAILABLE') AS available
            FROM borrow_requests r
            JOIN libraries l ON l.id = r.library_id
            JOIN users u ON u.id = r.member_id
            JOIN books b ON b.id = r.book_id
            LEFT JOIN book_copies c ON c.id = r.assigned_copy_id""";

    public PageResponse<RequestView> memberRequests(long memberId, String status, Long bookId, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("member", memberId);
        StringBuilder where = new StringBuilder(" WHERE r.member_id = :member");
        statusFilter(where, p, status);
        if (bookId != null) {
            where.append(" AND r.book_id = :bookId");
            p.addValue("bookId", bookId);
        }
        return requests(where.toString(), p, page, size, false);
    }

    public PageResponse<RequestView> libraryRequests(long libraryId, String status, String q, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("lib", libraryId);
        StringBuilder where = new StringBuilder(" WHERE r.library_id = :lib");
        statusFilter(where, p, status);
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q OR b.title LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        return requests(where.toString(), p, page, size, true);
    }

    public RequestView request(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        return requests(" WHERE r.id = :id", p, 0, 1, true).content().get(0);
    }

    private void statusFilter(StringBuilder where, MapSqlParameterSource p, String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return;
        }
        if ("ACTIVE".equalsIgnoreCase(status)) {
            where.append(" AND r.status IN ('PENDING','APPROVED')");
        } else if ("CLOSED".equalsIgnoreCase(status)) {
            where.append(" AND r.status IN ('REJECTED','CANCELLED','EXPIRED','FULFILLED')");
        } else {
            where.append(" AND r.status = :status");
            p.addValue("status", RequestStatus.valueOf(status.toUpperCase()).name());
        }
    }

    private PageResponse<RequestView> requests(String where, MapSqlParameterSource p, int page, int size, boolean staffView) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests r JOIN users u ON u.id = r.member_id"
                + " JOIN books b ON b.id = r.book_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Object[]> rows = jdbc.query(REQUEST_SELECT + where
                        + " ORDER BY (r.status = 'PENDING') DESC, (r.status = 'APPROVED') DESC, r.created_at DESC LIMIT :limit OFFSET :offset",
                p, (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("status"), rs.getLong("book_id"),
                        new LibraryRef(rs.getLong("library_id"), rs.getString("lib_name"), rs.getString("lib_slug")),
                        instant(rs, "created_at"), instant(rs, "decided_at"), rs.getString("decision_reason"),
                        date(rs, "pickup_deadline"), rs.getString("accession_no"), rs.getString("member_note"),
                        (Long) rs.getObject("borrowing_id", Long.class),
                        new MemberRef(rs.getLong("uid"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone")),
                        rs.getInt("available")});
        Map<Long, BookSummary> books = catalogue.summaries(rows.stream().map(r -> (Long) r[2]).toList());
        List<RequestView> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(new RequestView((Long) r[0], RequestStatus.valueOf((String) r[1]), books.get((Long) r[2]), (LibraryRef) r[3],
                    (Instant) r[4], (Instant) r[5], (String) r[6], (LocalDate) r[7], (String) r[8], (String) r[9], (Long) r[10],
                    staffView ? (MemberRef) r[11] : null, staffView ? (Integer) r[12] : null));
        }
        return PageResponse.of(out, page, size, total == null ? 0 : total);
    }

    private static final String LOAN_SELECT = """
            SELECT bo.id, bo.status, bo.book_id, bo.library_id, l.name AS lib_name, l.slug AS lib_slug, l.timezone,
                   bo.issued_at, bo.due_date, bo.returned_at, bo.return_condition, c.accession_no,
                   COALESCE(p.fine_per_day_paise, 0) AS per_day, COALESCE(p.grace_days, 0) AS grace,
                   COALESCE(p.max_fine_per_loan_paise, 0) AS cap,
                   u.id AS uid, u.full_name, u.email, u.phone
            FROM borrowings bo
            JOIN libraries l ON l.id = bo.library_id
            JOIN users u ON u.id = bo.member_id
            JOIN books b ON b.id = bo.book_id
            JOIN book_copies c ON c.id = bo.copy_id
            LEFT JOIN library_policies p ON p.library_id = bo.library_id""";

    public PageResponse<LoanView> memberLoans(long memberId, String filter, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("member", memberId);
        StringBuilder where = new StringBuilder(" WHERE bo.member_id = :member");
        String order = loanFilter(where, p, filter);
        return loans(where.toString(), p, order, page, size, false);
    }

    public PageResponse<LoanView> libraryLoans(long libraryId, String filter, String q, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("lib", libraryId);
        StringBuilder where = new StringBuilder(" WHERE bo.library_id = :lib");
        String order = loanFilter(where, p, filter);
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.full_name LIKE :q OR u.email LIKE :q OR b.title LIKE :q OR c.accession_no LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        return loans(where.toString(), p, order, page, size, true);
    }

    public LoanView loan(long id) {
        return loans(" WHERE bo.id = :id", new MapSqlParameterSource("id", id), " ORDER BY bo.id", 0, 1, true).content().get(0);
    }

    private String loanFilter(StringBuilder where, MapSqlParameterSource p, String filter) {
        String f = filter == null ? "ALL" : filter.toUpperCase();
        p.addValue("today", Date.valueOf(clock.today(AppClock.DEFAULT_ZONE)));
        switch (f) {
            case "ACTIVE" -> {
                where.append(" AND bo.status = 'ACTIVE'");
                return " ORDER BY bo.due_date ASC, bo.id ASC";
            }
            case "OVERDUE" -> {
                where.append(" AND bo.status = 'ACTIVE' AND bo.due_date < :today");
                return " ORDER BY bo.due_date ASC, bo.id ASC";
            }
            case "DUE_SOON" -> {
                where.append(" AND bo.status = 'ACTIVE' AND bo.due_date BETWEEN :today AND DATE_ADD(:today, INTERVAL 2 DAY)");
                return " ORDER BY bo.due_date ASC";
            }
            case "HISTORY", "RETURNED" -> {
                where.append(" AND bo.status IN ('RETURNED','LOST')");
                return " ORDER BY bo.returned_at DESC, bo.id DESC";
            }
            default -> {
                return " ORDER BY (bo.status = 'ACTIVE') DESC, bo.due_date ASC, bo.issued_at DESC";
            }
        }
    }

    private PageResponse<LoanView> loans(String where, MapSqlParameterSource p, String order, int page, int size, boolean staffView) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM borrowings bo JOIN users u ON u.id = bo.member_id"
                + " JOIN books b ON b.id = bo.book_id JOIN book_copies c ON c.id = bo.copy_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Object[]> rows = jdbc.query(LOAN_SELECT + where + order + " LIMIT :limit OFFSET :offset", p,
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("status"), rs.getLong("book_id"),
                        new LibraryRef(rs.getLong("library_id"), rs.getString("lib_name"), rs.getString("lib_slug")),
                        instant(rs, "issued_at"), date(rs, "due_date"), instant(rs, "returned_at"), rs.getString("return_condition"),
                        rs.getString("accession_no"), rs.getLong("per_day"), rs.getInt("grace"), rs.getLong("cap"),
                        rs.getString("timezone"),
                        new MemberRef(rs.getLong("uid"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone"))});
        Map<Long, BookSummary> books = catalogue.summaries(rows.stream().map(r -> (Long) r[2]).toList());
        List<LoanView> out = new ArrayList<>();
        for (Object[] r : rows) {
            BorrowingStatus status = BorrowingStatus.valueOf((String) r[1]);
            LocalDate due = (LocalDate) r[5];
            LocalDate today = clock.today((String) r[12]);
            boolean active = status == BorrowingStatus.ACTIVE;
            long overdueDays = active ? Math.max(0, ChronoUnit.DAYS.between(due, today)) : 0;
            long estimate = active ? FineCalculator.amount(due, today, (Integer) r[10], (Long) r[9], (Long) r[11]) : 0;
            boolean dueSoon = active && overdueDays == 0 && ChronoUnit.DAYS.between(today, due) <= 2;
            String cond = (String) r[7];
            out.add(new LoanView((Long) r[0], status, books.get((Long) r[2]), (LibraryRef) r[3], (Instant) r[4], due, (Instant) r[6],
                    cond == null ? null : ReturnCondition.valueOf(cond), overdueDays > 0, overdueDays, dueSoon, estimate, (String) r[8],
                    staffView ? (MemberRef) r[13] : null));
        }
        return PageResponse.of(out, page, size, total == null ? 0 : total);
    }

    public static final Set<String> LOAN_FILTERS = Set.of("ALL", "ACTIVE", "OVERDUE", "DUE_SOON", "HISTORY", "RETURNED");

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }
}
