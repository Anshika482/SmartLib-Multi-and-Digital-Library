package com.smartlib.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.common.config.AppProperties;

/** Removes self-registered accounts that never verified their email, so the address can be registered again. */
@Component
public class AccountCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AccountCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final AppProperties properties;

    public AccountCleanupJob(JdbcTemplate jdbc, AppProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public int purgeUnverified() {
        Instant cutoff = Instant.now().minus(properties.verification().unverifiedRetention());
        List<Long> ids = jdbc.queryForList("""
                SELECT u.id FROM users u
                WHERE u.email_verified_at IS NULL AND u.created_at < ? AND u.status = 'ACTIVE'
                  AND (u.role IN ('MEMBER','LIBRARIAN')
                       OR EXISTS (SELECT 1 FROM staff_assignments s WHERE s.user_id = u.id AND s.approval_status = 'PENDING'))
                  AND NOT EXISTS (SELECT 1 FROM borrow_requests r WHERE r.member_id = u.id)
                  AND NOT EXISTS (SELECT 1 FROM borrowings b WHERE b.member_id = u.id)
                  AND NOT EXISTS (SELECT 1 FROM fines f WHERE f.member_id = u.id)
                LIMIT 500""", Long.class, Timestamp.from(cutoff));
        for (Long id : ids) {
            for (String table : List.of("email_verifications", "account_tokens", "refresh_tokens", "notifications",
                    "staff_assignments", "library_memberships")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            }
            jdbc.update("DELETE FROM ai_conversations WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        if (!ids.isEmpty()) {
            log.info("Purged {} unverified accounts older than the retention window", ids.size());
        }
        return ids.size();
    }
}
