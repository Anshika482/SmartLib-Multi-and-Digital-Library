package com.smartlib.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;

@Service
public class AuditQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AuditEntry(long id, Instant occurredAt, Long actorUserId, String actorRole, String actorEmail, Long libraryId,
                             String libraryName, String action, String entityType, String entityId, String outcome, String ip,
                             String requestId, String details) {
    }

    /** @param libraryId restrict to one library (library admins); null = platform-wide (super admin). */
    public PageResponse<AuditEntry> search(Long libraryId, String action, String q, String outcome, LocalDate from, LocalDate to,
                                           int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (libraryId != null) {
            where.append(" AND a.library_id = :libraryId");
            params.addValue("libraryId", libraryId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = :action");
            params.addValue("action", action.trim());
        }
        if (outcome != null && !outcome.isBlank()) {
            where.append(" AND a.outcome = :outcome");
            params.addValue("outcome", outcome.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (a.actor_email LIKE :q OR a.entity_id = :qExact OR a.action LIKE :q)");
            params.addValue("q", Texts.likeContains(q.trim()));
            params.addValue("qExact", q.trim());
        }
        if (from != null) {
            where.append(" AND a.occurred_at >= :from");
            params.addValue("from", Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (to != null) {
            where.append(" AND a.occurred_at < :to");
            params.addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs a" + where, params, Long.class);
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<AuditEntry> rows = new ArrayList<>(jdbc.query(
                "SELECT a.*, l.name AS library_name FROM audit_logs a LEFT JOIN libraries l ON l.id = a.library_id" + where
                        + " ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit OFFSET :offset",
                params,
                (rs, i) -> new AuditEntry(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(),
                        (Long) rs.getObject("actor_user_id", Long.class), rs.getString("actor_role"), rs.getString("actor_email"),
                        (Long) rs.getObject("library_id", Long.class), rs.getString("library_name"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("outcome"), rs.getString("ip"),
                        rs.getString("request_id"), rs.getString("details"))));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }
}
