package com.smartlib.library;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.smartlib.common.util.Texts;

/** Public library directory with real aggregate counts (no private operational data). */
@Service
public class LibraryQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    public LibraryQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LibraryCard(long id, String code, String slug, String name, String tagline, String description, String addressLine,
                              String city, String state, String postalCode, String publicEmail, String publicPhone, String website,
                              String openingHours, String imageUrl, long titles, long copies, long availableCopies,
                              long digitalResources) {
    }

    public record PolicySummary(int loanPeriodDays, int maxActiveLoans, int pickupWindowDays, long finePerDayPaise, int graceDays) {
    }

    public record LibraryDetails(LibraryCard library, PolicySummary policy) {
    }

    public record PlatformStats(long libraries, long titles, long copies, long availableCopies, long digitalResources) {
    }

    private static final String CARD_SQL = """
            SELECT l.*,
              (SELECT COUNT(*) FROM library_holdings h WHERE h.library_id = l.id AND h.status = 'ACTIVE') AS titles,
              (SELECT COUNT(*) FROM book_copies c JOIN library_holdings h ON h.id = c.holding_id
                 WHERE c.library_id = l.id AND h.status = 'ACTIVE' AND c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS copies,
              (SELECT COUNT(*) FROM book_copies c JOIN library_holdings h ON h.id = c.holding_id
                 WHERE c.library_id = l.id AND h.status = 'ACTIVE' AND c.status = 'AVAILABLE') AS available,
              (SELECT COUNT(*) FROM digital_resources r WHERE r.library_id = l.id AND r.status = 'ACTIVE') AS resources
            FROM libraries l""";

    public List<LibraryCard> list(String q, String city) {
        StringBuilder where = new StringBuilder(" WHERE l.status = 'ACTIVE'");
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (q != null && !q.isBlank()) {
            where.append(" AND (l.name LIKE :q OR l.city LIKE :q OR l.description LIKE :q OR l.tagline LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        if (city != null && !city.isBlank()) {
            where.append(" AND l.city = :city");
            p.addValue("city", city.trim());
        }
        return jdbc.query(CARD_SQL + where + " ORDER BY l.name", p, (rs, i) -> card(rs));
    }

    public Optional<LibraryDetails> bySlug(String slug) {
        List<LibraryCard> found = jdbc.query(CARD_SQL + " WHERE l.status = 'ACTIVE' AND l.slug = :slug",
                new MapSqlParameterSource("slug", slug), (rs, i) -> card(rs));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LibraryCard card = found.get(0);
        List<PolicySummary> policy = jdbc.query("SELECT * FROM library_policies WHERE library_id = :id",
                new MapSqlParameterSource("id", card.id()),
                (rs, i) -> new PolicySummary(rs.getInt("loan_period_days"), rs.getInt("max_active_loans"), rs.getInt("pickup_window_days"),
                        rs.getLong("fine_per_day_paise"), rs.getInt("grace_days")));
        return Optional.of(new LibraryDetails(card, policy.isEmpty() ? null : policy.get(0)));
    }

    public PlatformStats stats() {
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM libraries WHERE status = 'ACTIVE') AS libraries,
                  (SELECT COUNT(DISTINCT h.book_id) FROM library_holdings h JOIN libraries l ON l.id = h.library_id
                     WHERE h.status = 'ACTIVE' AND l.status = 'ACTIVE') AS titles,
                  (SELECT COUNT(*) FROM book_copies c JOIN library_holdings h ON h.id = c.holding_id JOIN libraries l ON l.id = h.library_id
                     WHERE h.status = 'ACTIVE' AND l.status = 'ACTIVE' AND c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS copies,
                  (SELECT COUNT(*) FROM book_copies c JOIN library_holdings h ON h.id = c.holding_id JOIN libraries l ON l.id = h.library_id
                     WHERE h.status = 'ACTIVE' AND l.status = 'ACTIVE' AND c.status = 'AVAILABLE') AS available,
                  (SELECT COUNT(*) FROM digital_resources r JOIN libraries l ON l.id = r.library_id
                     WHERE r.status = 'ACTIVE' AND l.status = 'ACTIVE') AS resources""",
                new MapSqlParameterSource(),
                (rs, i) -> new PlatformStats(rs.getLong("libraries"), rs.getLong("titles"), rs.getLong("copies"), rs.getLong("available"),
                        rs.getLong("resources")));
    }

    private static LibraryCard card(java.sql.ResultSet rs) throws java.sql.SQLException {
        String imageKey = rs.getString("image_key");
        String slug = rs.getString("slug");
        return new LibraryCard(rs.getLong("id"), rs.getString("code"), slug, rs.getString("name"), rs.getString("tagline"),
                rs.getString("description"), rs.getString("address_line"), rs.getString("city"), rs.getString("state"),
                rs.getString("postal_code"), rs.getString("public_email"), rs.getString("public_phone"), rs.getString("website"),
                rs.getString("opening_hours"), imageKey == null ? null : "/api/public/libraries/" + slug + "/image",
                rs.getLong("titles"), rs.getLong("copies"), rs.getLong("available"), rs.getLong("resources"));
    }
}
