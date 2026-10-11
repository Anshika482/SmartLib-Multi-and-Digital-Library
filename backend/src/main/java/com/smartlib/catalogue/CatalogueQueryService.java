package com.smartlib.catalogue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.smartlib.catalogue.CatalogueDtos.BookDetails;
import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.catalogue.CatalogueDtos.CatalogueItem;
import com.smartlib.catalogue.CatalogueDtos.CatalogueQuery;
import com.smartlib.catalogue.CatalogueDtos.CategoryRef;
import com.smartlib.catalogue.CatalogueDtos.CategoryWithCount;
import com.smartlib.catalogue.CatalogueDtos.HoldingAvailability;
import com.smartlib.catalogue.CatalogueDtos.HoldingDetail;
import com.smartlib.catalogue.CatalogueDtos.ResourceSummary;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;

/**
 * Database-powered public catalogue. Search spans ALL active libraries unless a library filter is supplied; the
 * library filter only restricts results, it never changes how text matching works. Each page needs a fixed number of
 * indexed queries (page, count, availability, authors) regardless of page size — no N+1 and no full-catalogue scans.
 */
@Service
public class CatalogueQueryService {

    private static final String BOOK_COLUMNS = "b.id, b.title, b.subtitle, b.isbn13, b.isbn10, b.published_year, b.language, b.publisher,"
            + " b.cover_source, b.cover_key, b.cover_external_ref, b.updated_at, c.id AS cat_id, c.name AS cat_name, c.slug AS cat_slug";
    private static final String ACTIVE_HOLDING = "SELECT 1 FROM library_holdings h JOIN libraries l ON l.id = h.library_id"
            + " WHERE h.book_id = b.id AND h.status = 'ACTIVE' AND l.status = 'ACTIVE'";

    private final NamedParameterJdbcTemplate jdbc;
    private final CoverUrls covers;

    public CatalogueQueryService(NamedParameterJdbcTemplate jdbc, CoverUrls covers) {
        this.jdbc = jdbc;
        this.covers = covers;
    }

    private record Criteria(String where, MapSqlParameterSource params, String scoreExpr) {
    }

    private Criteria criteria(CatalogueQuery q) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder w = new StringBuilder(" WHERE EXISTS (").append(ACTIVE_HOLDING);
        if (q.libraryId() != null) {
            w.append(" AND h.library_id = :libraryId");
            p.addValue("libraryId", q.libraryId());
        }
        w.append(')');
        String score = null;
        String text = q.q() == null ? "" : q.q().trim();
        if (!text.isEmpty()) {
            Optional<IsbnUtils.Isbn> isbn = IsbnUtils.looksLikeIsbn(text) ? IsbnUtils.parse(text) : Optional.empty();
            if (isbn.isPresent()) {
                w.append(" AND b.isbn13 = :isbn13");
                p.addValue("isbn13", isbn.get().isbn13());
            } else {
                String ft = q.matchAny() ? SearchTerms.looseQuery(text) : SearchTerms.booleanQuery(text);
                if (ft != null) {
                    w.append(" AND MATCH(b.search_text) AGAINST(:ft IN BOOLEAN MODE)");
                    p.addValue("ft", ft);
                    score = "MATCH(b.search_text) AGAINST(:ft IN BOOLEAN MODE)";
                    if (!q.matchAny()) {
                        List<String> shorts = SearchTerms.shortTokens(text);
                        for (int i = 0; i < shorts.size(); i++) {
                            w.append(" AND b.search_text LIKE :short").append(i);
                            p.addValue("short" + i, Texts.likeContains(shorts.get(i)));
                        }
                    }
                } else {
                    w.append(" AND b.search_text LIKE :like");
                    p.addValue("like", Texts.likeContains(text));
                }
            }
        }
        if (q.categoryId() != null) {
            w.append(" AND b.category_id = :categoryId");
            p.addValue("categoryId", q.categoryId());
        } else if (q.categorySlug() != null && !q.categorySlug().isBlank()) {
            w.append(" AND b.category_id = (SELECT id FROM categories WHERE slug = :categorySlug)");
            p.addValue("categorySlug", q.categorySlug().trim());
        }
        if (q.language() != null && !q.language().isBlank()) {
            w.append(" AND b.language = :language");
            p.addValue("language", q.language().trim());
        }
        if (q.availableOnly()) {
            w.append(" AND EXISTS (SELECT 1 FROM book_copies bc JOIN library_holdings h3 ON h3.id = bc.holding_id"
                    + " JOIN libraries l3 ON l3.id = h3.library_id WHERE h3.book_id = b.id AND h3.status = 'ACTIVE'"
                    + " AND l3.status = 'ACTIVE' AND bc.status = 'AVAILABLE'");
            if (q.libraryId() != null) {
                w.append(" AND h3.library_id = :libraryId");
            }
            w.append(')');
        }
        return new Criteria(w.toString(), p, score);
    }

    public PageResponse<CatalogueItem> search(CatalogueQuery q) {
        Criteria c = criteria(q);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM books b" + c.where(), c.params(), Long.class);
        long count = total == null ? 0 : total;
        if (count == 0) {
            return PageResponse.of(List.of(), q.page(), q.size(), 0);
        }
        String order = switch (q.sort() == null ? "" : q.sort()) {
            case "title" -> " ORDER BY b.title ASC, b.id ASC";
            case "newest" -> " ORDER BY b.created_at DESC, b.id DESC";
            case "year" -> " ORDER BY b.published_year IS NULL, b.published_year DESC, b.title ASC";
            case "popular" -> {
                c.params().addValue("since", Timestamp.from(Instant.now().minus(Duration.ofDays(90))));
                yield " ORDER BY (SELECT COUNT(*) FROM borrowings bo WHERE bo.book_id = b.id AND bo.issued_at >= :since) DESC, b.title ASC";
            }
            default -> c.scoreExpr() != null ? " ORDER BY " + c.scoreExpr() + " DESC, b.title ASC, b.id ASC" : " ORDER BY b.title ASC, b.id ASC";
        };
        c.params().addValue("limit", q.size()).addValue("offset", (long) q.page() * q.size());
        List<BookRow> rows = jdbc.query("SELECT " + BOOK_COLUMNS + " FROM books b LEFT JOIN categories c ON c.id = b.category_id"
                + c.where() + order + " LIMIT :limit OFFSET :offset", c.params(), (rs, i) -> bookRow(rs));
        return PageResponse.of(toItems(rows, q.libraryId()), q.page(), q.size(), count);
    }

    /** Most-borrowed titles in the last 90 days, falling back to the newest additions. */
    public List<CatalogueItem> featured(int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("limit", limit)
                .addValue("since", Timestamp.from(Instant.now().minus(Duration.ofDays(90))));
        List<BookRow> rows = jdbc.query("SELECT " + BOOK_COLUMNS + " FROM books b LEFT JOIN categories c ON c.id = b.category_id"
                + " WHERE EXISTS (" + ACTIVE_HOLDING + ")"
                + " ORDER BY (SELECT COUNT(*) FROM borrowings bo WHERE bo.book_id = b.id AND bo.issued_at >= :since) DESC,"
                + " b.created_at DESC, b.id DESC LIMIT :limit", p, (rs, i) -> bookRow(rs));
        return toItems(rows, null);
    }

    public List<CategoryWithCount> categories() {
        return jdbc.query("SELECT c.id, c.name, c.slug, (SELECT COUNT(*) FROM books b WHERE b.category_id = c.id AND EXISTS ("
                        + ACTIVE_HOLDING + ")) AS book_count FROM categories c ORDER BY c.sort_order, c.name",
                new MapSqlParameterSource(),
                (rs, i) -> new CategoryWithCount(rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getLong("book_count")));
    }

    public List<String> languages() {
        return jdbc.queryForList("SELECT DISTINCT b.language FROM books b WHERE b.language IS NOT NULL AND EXISTS (" + ACTIVE_HOLDING
                + ") ORDER BY b.language", new MapSqlParameterSource(), String.class);
    }

    public Optional<BookDetails> details(long bookId) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", bookId);
        List<Map.Entry<BookRow, String[]>> found = jdbc.query("SELECT " + BOOK_COLUMNS + ", b.description, b.page_count"
                        + " FROM books b LEFT JOIN categories c ON c.id = b.category_id WHERE b.id = :id", p,
                (rs, i) -> Map.entry(bookRow(rs), new String[]{rs.getString("description"), rs.getString("page_count")}));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BookRow b = found.get(0).getKey();
        String description = found.get(0).getValue()[0];
        Integer pageCount = found.get(0).getValue()[1] == null ? null : Integer.valueOf(found.get(0).getValue()[1]);

        List<HoldingDetail> holdings = jdbc.query("""
                SELECT h.id AS holding_id, l.id AS library_id, l.name, l.slug, l.city, l.address_line, l.opening_hours, h.shelf_location,
                       COALESCE(SUM(bc.status = 'AVAILABLE'), 0) AS available,
                       COALESCE(SUM(bc.status IN ('AVAILABLE','RESERVED','BORROWED')), 0) AS total,
                       COALESCE(MAX(p.loan_period_days), 14) AS loan_days, COALESCE(MAX(p.pickup_window_days), 3) AS pickup_days,
                       COALESCE(MAX(p.fine_per_day_paise), 0) AS fine_per_day
                FROM library_holdings h
                JOIN libraries l ON l.id = h.library_id
                LEFT JOIN library_policies p ON p.library_id = l.id
                LEFT JOIN book_copies bc ON bc.holding_id = h.id
                WHERE h.book_id = :id AND h.status = 'ACTIVE' AND l.status = 'ACTIVE'
                GROUP BY h.id, l.id, l.name, l.slug, l.city, l.address_line, l.opening_hours, h.shelf_location
                ORDER BY available DESC, l.name ASC""", p,
                (rs, i) -> new HoldingDetail(rs.getLong("holding_id"), rs.getLong("library_id"), rs.getString("name"), rs.getString("slug"),
                        rs.getString("city"), rs.getString("address_line"), rs.getString("opening_hours"), rs.getString("shelf_location"),
                        rs.getInt("available"), rs.getInt("total"), rs.getInt("loan_days"), rs.getInt("pickup_days"), rs.getLong("fine_per_day")));
        if (holdings.isEmpty()) {
            return Optional.empty(); // not part of any active library's catalogue
        }
        List<ResourceSummary> resources = jdbc.query("""
                SELECT r.id, r.title, r.type, r.visibility, l.id AS library_id, l.name AS library_name
                FROM digital_resources r JOIN libraries l ON l.id = r.library_id
                WHERE r.book_id = :id AND r.status = 'ACTIVE' AND l.status = 'ACTIVE' ORDER BY r.created_at DESC""", p,
                (rs, i) -> new ResourceSummary(rs.getLong("id"), rs.getString("title"), rs.getString("type"), rs.getString("visibility"),
                        rs.getLong("library_id"), rs.getString("library_name")));
        List<CatalogueItem> related = List.of();
        if (b.categoryId() != null) {
            MapSqlParameterSource rp = new MapSqlParameterSource("cat", b.categoryId()).addValue("id", bookId);
            List<BookRow> rel = jdbc.query("SELECT " + BOOK_COLUMNS + " FROM books b LEFT JOIN categories c ON c.id = b.category_id"
                    + " WHERE b.category_id = :cat AND b.id <> :id AND EXISTS (" + ACTIVE_HOLDING + ")"
                    + " ORDER BY b.created_at DESC LIMIT 6", rp, (rs, i) -> bookRow(rs));
            related = toItems(rel, null);
        }
        Map<Long, List<String>> authors = authors(List.of(bookId));
        int totalAvailable = holdings.stream().mapToInt(HoldingDetail::availableCopies).sum();
        return Optional.of(new BookDetails(b.id(), b.title(), b.subtitle(), authors.getOrDefault(bookId, List.of()), b.isbn13(),
                b.isbn10(), description, b.publisher(), b.publishedYear(), b.language(), pageCount, b.category(),
                covers.coverUrl(b.id(), b.coverSource(), b.coverKey(), b.coverRef(), b.updatedAt()),
                covers.isbnCoverUrl(b.isbn13(), b.coverSource()), holdings, totalAvailable, resources, related));
    }

    /** Lightweight summaries used by other modules (requests, loans, fines, AI cards). */
    public Map<Long, BookSummary> summaries(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<BookRow> rows = jdbc.query("SELECT " + BOOK_COLUMNS + " FROM books b LEFT JOIN categories c ON c.id = b.category_id"
                + " WHERE b.id IN (:ids)", new MapSqlParameterSource("ids", ids), (rs, i) -> bookRow(rs));
        Map<Long, List<String>> authors = authors(rows.stream().map(BookRow::id).toList());
        Map<Long, BookSummary> out = new LinkedHashMap<>();
        for (BookRow b : rows) {
            out.put(b.id(), new BookSummary(b.id(), b.title(), b.subtitle(), authors.getOrDefault(b.id(), List.of()), b.isbn13(),
                    covers.coverUrl(b.id(), b.coverSource(), b.coverKey(), b.coverRef(), b.updatedAt()),
                    covers.isbnCoverUrl(b.isbn13(), b.coverSource()), b.category() == null ? null : b.category().name()));
        }
        return out;
    }

    // ---------- internals ----------

    record BookRow(long id, String title, String subtitle, String isbn13, String isbn10, Integer publishedYear, String language,
                   String publisher, String coverSource, String coverKey, String coverRef, Instant updatedAt, CategoryRef category,
                   Long categoryId) {
    }

    private static BookRow bookRow(ResultSet rs) throws SQLException {
        long catId = rs.getLong("cat_id");
        boolean hasCat = !rs.wasNull();
        Timestamp updated = rs.getTimestamp("updated_at");
        int year = rs.getInt("published_year");
        Integer publishedYear = rs.wasNull() ? null : year;
        return new BookRow(rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"), rs.getString("isbn13"),
                rs.getString("isbn10"), publishedYear, rs.getString("language"), rs.getString("publisher"),
                rs.getString("cover_source"), rs.getString("cover_key"), rs.getString("cover_external_ref"),
                updated == null ? null : updated.toInstant(),
                hasCat ? new CategoryRef(catId, rs.getString("cat_name"), rs.getString("cat_slug")) : null, hasCat ? catId : null);
    }

    private List<CatalogueItem> toItems(List<BookRow> rows, Long libraryId) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(BookRow::id).toList();
        Map<Long, List<HoldingAvailability>> holdings = holdings(ids, libraryId);
        Map<Long, List<String>> authors = authors(ids);
        List<CatalogueItem> items = new ArrayList<>(rows.size());
        for (BookRow b : rows) {
            List<HoldingAvailability> hs = holdings.getOrDefault(b.id(), List.of());
            int available = hs.stream().mapToInt(HoldingAvailability::availableCopies).sum();
            items.add(new CatalogueItem(b.id(), b.title(), b.subtitle(), authors.getOrDefault(b.id(), List.of()), b.isbn13(),
                    covers.coverUrl(b.id(), b.coverSource(), b.coverKey(), b.coverRef(), b.updatedAt()),
                    covers.isbnCoverUrl(b.isbn13(), b.coverSource()), b.category(), b.publishedYear(), b.language(), b.publisher(),
                    hs, available, hs.size()));
        }
        return items;
    }

    private Map<Long, List<HoldingAvailability>> holdings(List<Long> bookIds, Long libraryId) {
        MapSqlParameterSource p = new MapSqlParameterSource("ids", bookIds);
        String scope = "";
        if (libraryId != null) {
            scope = " AND h.library_id = :libraryId";
            p.addValue("libraryId", libraryId);
        }
        Map<Long, List<HoldingAvailability>> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT h.id AS holding_id, h.book_id, l.id AS library_id, l.name, l.slug, l.city,
                       COALESCE(SUM(bc.status = 'AVAILABLE'), 0) AS available,
                       COALESCE(SUM(bc.status IN ('AVAILABLE','RESERVED','BORROWED')), 0) AS total
                FROM library_holdings h
                JOIN libraries l ON l.id = h.library_id
                LEFT JOIN book_copies bc ON bc.holding_id = h.id
                WHERE h.book_id IN (:ids) AND h.status = 'ACTIVE' AND l.status = 'ACTIVE'""" + scope + """

                GROUP BY h.id, h.book_id, l.id, l.name, l.slug, l.city
                ORDER BY available DESC, l.name ASC""", p, rs -> {
            out.computeIfAbsent(rs.getLong("book_id"), k -> new ArrayList<>()).add(new HoldingAvailability(rs.getLong("holding_id"),
                    rs.getLong("library_id"), rs.getString("name"), rs.getString("slug"), rs.getString("city"),
                    rs.getInt("available"), rs.getInt("total")));
        });
        return out;
    }

    private Map<Long, List<String>> authors(List<Long> bookIds) {
        Map<Long, List<String>> out = new LinkedHashMap<>();
        if (bookIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT ba.book_id, a.name FROM book_authors ba JOIN authors a ON a.id = ba.author_id"
                        + " WHERE ba.book_id IN (:ids) ORDER BY ba.book_id, ba.position", new MapSqlParameterSource("ids", bookIds),
                rs -> {
                    out.computeIfAbsent(rs.getLong("book_id"), k -> new ArrayList<>()).add(rs.getString("name"));
                });
        return out;
    }
}
