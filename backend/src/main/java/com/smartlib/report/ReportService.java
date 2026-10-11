package com.smartlib.report;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.catalogue.CatalogueQueryService;
import com.smartlib.common.time.AppClock;

/** All dashboard numbers come from these aggregate queries over real data — nothing is hard-coded. */
@Service
public class ReportService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogueQueryService catalogue;
    private final AppClock clock;

    public ReportService(NamedParameterJdbcTemplate jdbc, CatalogueQueryService catalogue, AppClock clock) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    public record LibrarySummary(long titles, long copies, long availableCopies, long reservedCopies, long borrowedCopies,
                                 long lostOrDamaged, long activeLoans, long overdueLoans, long dueSoon, long pendingRequests,
                                 long readyForPickup, long totalMembers, long activeMembers90d, long finesOutstandingPaise,
                                 long finesCollected30dPaise, long finesCollectedTotalPaise, long digitalResources,
                                 long pendingLibrarianApplications, long loansThisMonth) {
    }

    public record PopularBook(BookSummary book, long loans) {
    }

    public record MonthPoint(String month, long issued, long returned, long newMembers, long finesCollectedPaise) {
    }

    public record CategoryPoint(String category, long titles, long loans90d) {
    }

    public record LibraryRow(long id, String code, String name, String city, String status, long titles, long copies, long activeLoans,
                             long overdueLoans, long members, long pendingRequests, long finesCollectedPaise, long staff) {
    }

    public record PlatformOverview(long libraries, long activeLibraries, long members, long librarians, long admins, long superAdmins,
                                   long titles, long copies, long availableCopies, long activeLoans, long overdueLoans, long totalLoans,
                                   long finesCollectedPaise, long finesOutstandingPaise, long digitalResources,
                                   long pendingLibrarianApplications, long aiMessages30d, Long aiAvgLatencyMs, Long aiAvgFirstTokenMs,
                                   long newMembers30d, long pendingAdminApplications) {
    }

    private MapSqlParameterSource base(Long libraryId) {
        LocalDate today = clock.today(AppClock.DEFAULT_ZONE);
        return new MapSqlParameterSource("lib", libraryId)
                .addValue("today", Date.valueOf(today))
                .addValue("soon", Date.valueOf(today.plusDays(2)))
                .addValue("since90", Timestamp.from(clock.now().minus(Duration.ofDays(90))))
                .addValue("since30", Timestamp.from(clock.now().minus(Duration.ofDays(30))))
                .addValue("monthStart", Timestamp.from(today.withDayOfMonth(1).atStartOfDay(AppClock.DEFAULT_ZONE).toInstant()));
    }

    public LibrarySummary librarySummary(long libraryId) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM library_holdings h WHERE h.library_id = :lib AND h.status = 'ACTIVE') AS titles,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = :lib AND c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS copies,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = :lib AND c.status = 'AVAILABLE') AS available,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = :lib AND c.status = 'RESERVED') AS reserved,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = :lib AND c.status = 'BORROWED') AS borrowed,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = :lib AND c.status IN ('LOST','DAMAGED')) AS lost_damaged,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = :lib AND b.status = 'ACTIVE') AS active_loans,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = :lib AND b.status = 'ACTIVE' AND b.due_date < :today) AS overdue,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = :lib AND b.status = 'ACTIVE' AND b.due_date BETWEEN :today AND :soon) AS due_soon,
                  (SELECT COUNT(*) FROM borrow_requests r WHERE r.library_id = :lib AND r.status = 'PENDING') AS pending,
                  (SELECT COUNT(*) FROM borrow_requests r WHERE r.library_id = :lib AND r.status = 'APPROVED') AS ready,
                  (SELECT COUNT(*) FROM library_memberships m WHERE m.library_id = :lib) AS members,
                  (SELECT COUNT(DISTINCT b.member_id) FROM borrowings b WHERE b.library_id = :lib AND b.issued_at >= :since90) AS active_members,
                  (SELECT COALESCE(SUM(f.amount_paise),0) FROM fines f WHERE f.library_id = :lib AND f.status = 'UNPAID') AS outstanding,
                  (SELECT COALESCE(SUM(p.amount_paise),0) FROM payments p WHERE p.library_id = :lib AND p.status = 'PAID' AND p.verified_at >= :since30) AS collected30,
                  (SELECT COALESCE(SUM(p.amount_paise),0) FROM payments p WHERE p.library_id = :lib AND p.status = 'PAID') AS collected,
                  (SELECT COUNT(*) FROM digital_resources d WHERE d.library_id = :lib AND d.status = 'ACTIVE') AS resources,
                  (SELECT COUNT(*) FROM staff_assignments s JOIN users u ON u.id = s.user_id
                     WHERE s.library_id = :lib AND s.approval_status = 'PENDING' AND u.role = 'LIBRARIAN') AS pending_apps,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = :lib AND b.issued_at >= :monthStart) AS loans_month
                """, base(libraryId), (rs, i) -> new LibrarySummary(rs.getLong("titles"), rs.getLong("copies"), rs.getLong("available"),
                rs.getLong("reserved"), rs.getLong("borrowed"), rs.getLong("lost_damaged"), rs.getLong("active_loans"), rs.getLong("overdue"),
                rs.getLong("due_soon"), rs.getLong("pending"), rs.getLong("ready"), rs.getLong("members"), rs.getLong("active_members"),
                rs.getLong("outstanding"), rs.getLong("collected30"), rs.getLong("collected"), rs.getLong("resources"),
                rs.getLong("pending_apps"), rs.getLong("loans_month")));
    }

    public List<PopularBook> popularBooks(Long libraryId, int days, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("since", Timestamp.from(clock.now().minus(Duration.ofDays(days))))
                .addValue("limit", limit);
        String scope = "";
        if (libraryId != null) {
            scope = " AND b.library_id = :lib";
            p.addValue("lib", libraryId);
        }
        List<long[]> rows = jdbc.query("SELECT b.book_id, COUNT(*) AS loans FROM borrowings b WHERE b.issued_at >= :since" + scope
                + " GROUP BY b.book_id ORDER BY loans DESC, b.book_id LIMIT :limit", p, (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});
        Map<Long, BookSummary> books = catalogue.summaries(rows.stream().map(r -> r[0]).toList());
        List<PopularBook> out = new ArrayList<>();
        for (long[] r : rows) {
            out.add(new PopularBook(books.get(r[0]), r[1]));
        }
        return out;
    }

    public List<MonthPoint> monthlyTrends(Long libraryId, int months) {
        YearMonth current = YearMonth.from(clock.today(AppClock.DEFAULT_ZONE));
        YearMonth first = current.minusMonths(months - 1L);
        MapSqlParameterSource p = new MapSqlParameterSource("from", Timestamp.from(first.atDay(1).atStartOfDay(AppClock.DEFAULT_ZONE).toInstant()));
        String lib = "";
        String libMember = "";
        if (libraryId != null) {
            p.addValue("lib", libraryId);
            lib = " AND library_id = :lib";
            libMember = " AND library_id = :lib";
        }
        Map<String, long[]> points = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            points.put(first.plusMonths(i).toString(), new long[4]);
        }
        merge(points, 0, "SELECT DATE_FORMAT(CONVERT_TZ(issued_at,'+00:00','+05:30'),'%Y-%m') m, COUNT(*) FROM borrowings WHERE issued_at >= :from" + lib + " GROUP BY m", p);
        merge(points, 1, "SELECT DATE_FORMAT(CONVERT_TZ(returned_at,'+00:00','+05:30'),'%Y-%m') m, COUNT(*) FROM borrowings WHERE returned_at >= :from" + lib + " GROUP BY m", p);
        if (libraryId != null) {
            merge(points, 2, "SELECT DATE_FORMAT(CONVERT_TZ(joined_at,'+00:00','+05:30'),'%Y-%m') m, COUNT(*) FROM library_memberships WHERE joined_at >= :from" + libMember + " GROUP BY m", p);
        } else {
            merge(points, 2, "SELECT DATE_FORMAT(CONVERT_TZ(created_at,'+00:00','+05:30'),'%Y-%m') m, COUNT(*) FROM users WHERE role = 'MEMBER' AND created_at >= :from GROUP BY m", p);
        }
        merge(points, 3, "SELECT DATE_FORMAT(CONVERT_TZ(verified_at,'+00:00','+05:30'),'%Y-%m') m, SUM(amount_paise) FROM payments WHERE status = 'PAID' AND verified_at >= :from" + lib + " GROUP BY m", p);
        List<MonthPoint> out = new ArrayList<>();
        points.forEach((m, v) -> out.add(new MonthPoint(m, v[0], v[1], v[2], v[3])));
        return out;
    }

    private void merge(Map<String, long[]> points, int idx, String sql, MapSqlParameterSource p) {
        jdbc.query(sql, p, rs -> {
            long[] v = points.get(rs.getString(1));
            if (v != null) {
                v[idx] = rs.getLong(2);
            }
        });
    }

    public List<CategoryPoint> categories(Long libraryId) {
        MapSqlParameterSource p = new MapSqlParameterSource("since90", Timestamp.from(clock.now().minus(Duration.ofDays(90))));
        String holdingScope = "";
        String loanScope = "";
        if (libraryId != null) {
            p.addValue("lib", libraryId);
            holdingScope = " AND h.library_id = :lib";
            loanScope = " AND bo.library_id = :lib";
        }
        return jdbc.query("SELECT c.name,"
                        + " (SELECT COUNT(DISTINCT h.book_id) FROM library_holdings h JOIN books b ON b.id = h.book_id WHERE b.category_id = c.id AND h.status = 'ACTIVE'" + holdingScope + ") AS titles,"
                        + " (SELECT COUNT(*) FROM borrowings bo JOIN books b ON b.id = bo.book_id WHERE b.category_id = c.id AND bo.issued_at >= :since90" + loanScope + ") AS loans"
                        + " FROM categories c ORDER BY c.sort_order", p,
                (rs, i) -> new CategoryPoint(rs.getString(1), rs.getLong(2), rs.getLong(3)));
    }

    public List<LibraryRow> libraryComparison() {
        return jdbc.query("""
                SELECT l.id, l.code, l.name, l.city, l.status,
                  (SELECT COUNT(*) FROM library_holdings h WHERE h.library_id = l.id AND h.status = 'ACTIVE') AS titles,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.library_id = l.id AND c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS copies,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = l.id AND b.status = 'ACTIVE') AS active_loans,
                  (SELECT COUNT(*) FROM borrowings b WHERE b.library_id = l.id AND b.status = 'ACTIVE' AND b.due_date < :today) AS overdue,
                  (SELECT COUNT(*) FROM library_memberships m WHERE m.library_id = l.id) AS members,
                  (SELECT COUNT(*) FROM borrow_requests r WHERE r.library_id = l.id AND r.status = 'PENDING') AS pending,
                  (SELECT COALESCE(SUM(p.amount_paise),0) FROM payments p WHERE p.library_id = l.id AND p.status = 'PAID') AS collected,
                  (SELECT COUNT(*) FROM staff_assignments s WHERE s.library_id = l.id AND s.approval_status = 'APPROVED') AS staff
                FROM libraries l ORDER BY l.name""", base(null),
                (rs, i) -> new LibraryRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("city"),
                        rs.getString("status"), rs.getLong("titles"), rs.getLong("copies"), rs.getLong("active_loans"), rs.getLong("overdue"),
                        rs.getLong("members"), rs.getLong("pending"), rs.getLong("collected"), rs.getLong("staff")));
    }

    public PlatformOverview platformOverview() {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM libraries) AS libraries,
                  (SELECT COUNT(*) FROM libraries WHERE status = 'ACTIVE') AS active_libraries,
                  (SELECT COUNT(*) FROM users WHERE role = 'MEMBER') AS members,
                  (SELECT COUNT(*) FROM users u JOIN staff_assignments s ON s.user_id = u.id WHERE u.role = 'LIBRARIAN' AND s.approval_status = 'APPROVED') AS librarians,
                  (SELECT COUNT(*) FROM users u JOIN staff_assignments s ON s.user_id = u.id WHERE u.role = 'ADMIN' AND s.approval_status = 'APPROVED') AS admins,
                  (SELECT COUNT(*) FROM users u LEFT JOIN staff_assignments s ON s.user_id = u.id WHERE u.role = 'SUPER_ADMIN' AND (s.id IS NULL OR s.approval_status = 'APPROVED')) AS supers,
                  (SELECT COUNT(DISTINCT h.book_id) FROM library_holdings h WHERE h.status = 'ACTIVE') AS titles,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS copies,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.status = 'AVAILABLE') AS available,
                  (SELECT COUNT(*) FROM borrowings WHERE status = 'ACTIVE') AS active_loans,
                  (SELECT COUNT(*) FROM borrowings WHERE status = 'ACTIVE' AND due_date < :today) AS overdue,
                  (SELECT COUNT(*) FROM borrowings) AS total_loans,
                  (SELECT COALESCE(SUM(amount_paise),0) FROM payments WHERE status = 'PAID') AS collected,
                  (SELECT COALESCE(SUM(amount_paise),0) FROM fines WHERE status = 'UNPAID') AS outstanding,
                  (SELECT COUNT(*) FROM digital_resources WHERE status = 'ACTIVE') AS resources,
                  (SELECT COUNT(*) FROM staff_assignments s JOIN users u ON u.id = s.user_id WHERE s.approval_status = 'PENDING' AND u.role = 'LIBRARIAN') AS pending_apps,
                  (SELECT COUNT(*) FROM ai_messages WHERE role = 'USER' AND created_at >= :since30) AS ai_messages,
                  (SELECT ROUND(AVG(latency_ms)) FROM ai_messages WHERE role = 'MODEL' AND latency_ms IS NOT NULL AND created_at >= :since30) AS ai_latency,
                  (SELECT ROUND(AVG(first_token_ms)) FROM ai_messages WHERE role = 'MODEL' AND first_token_ms IS NOT NULL AND created_at >= :since30) AS ai_ttft,
                  (SELECT COUNT(*) FROM users WHERE role = 'MEMBER' AND created_at >= :since30) AS new_members,
                  (SELECT COUNT(*) FROM staff_assignments s JOIN users u ON u.id = s.user_id
                     WHERE s.approval_status = 'PENDING' AND u.role IN ('ADMIN', 'SUPER_ADMIN') AND u.email_verified_at IS NOT NULL) AS pending_admin_apps
                """, base(null), (rs, i) -> {
            long lat = rs.getLong("ai_latency");
            Long latency = rs.wasNull() ? null : lat;
            long ft = rs.getLong("ai_ttft");
            Long ttft = rs.wasNull() ? null : ft;
            return new PlatformOverview(rs.getLong("libraries"), rs.getLong("active_libraries"), rs.getLong("members"), rs.getLong("librarians"),
                    rs.getLong("admins"), rs.getLong("supers"), rs.getLong("titles"), rs.getLong("copies"), rs.getLong("available"),
                    rs.getLong("active_loans"), rs.getLong("overdue"), rs.getLong("total_loans"), rs.getLong("collected"),
                    rs.getLong("outstanding"), rs.getLong("resources"), rs.getLong("pending_apps"), rs.getLong("ai_messages"), latency, ttft,
                    rs.getLong("new_members"), rs.getLong("pending_admin_apps"));
        });
    }

    public Instant now() {
        return clock.now();
    }
}
