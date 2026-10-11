package com.smartlib.circulation;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;

@Component
public class CirculationJobs {

    private static final Logger log = LoggerFactory.getLogger(CirculationJobs.class);

    private final BorrowRequestRepository requests;
    private final CirculationService circulation;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final AppClock clock;

    public CirculationJobs(BorrowRequestRepository requests, CirculationService circulation, JdbcTemplate jdbc,
                           ApplicationEventPublisher events, TransactionTemplate tx, AppClock clock) {
        this.requests = requests;
        this.circulation = circulation;
        this.jdbc = jdbc;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public int expirePickups() {
        int n = 0;
        for (Long id : requests.findExpiredPickups(clock.today(AppClock.DEFAULT_ZONE))) {
            try {
                if (circulation.expirePickup(id)) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire request {}: {}", id, e.getMessage());
            }
        }
        return n;
    }

    /** Due-soon, due-today and overdue reminders; reminder_log makes each reminder fire at most once per day. */
    @Scheduled(cron = "0 0 * * * *")
    public int sendDueReminders() {
        record Row(long id, String timezone, LocalDate due) {
        }
        List<Row> rows = jdbc.query("""
                SELECT b.id, l.timezone, b.due_date FROM borrowings b JOIN libraries l ON l.id = b.library_id
                WHERE b.status = 'ACTIVE' AND b.due_date <= ?""",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate()),
                Date.valueOf(clock.today(AppClock.DEFAULT_ZONE).plusDays(3)));
        int sent = 0;
        for (Row r : rows) {
            LocalDate today = clock.today(r.timezone());
            long daysUntil = ChronoUnit.DAYS.between(today, r.due());
            String kind = null;
            if (daysUntil == 2) {
                kind = "DUE_SOON";
            } else if (daysUntil == 0) {
                kind = "DUE_TODAY";
            } else if (daysUntil < 0 && (-daysUntil - 1) % 3 == 0) {
                kind = "OVERDUE";
            }
            if (kind == null) {
                continue;
            }
            String k = kind;
            Boolean fired = tx.execute(s -> {
                int inserted = jdbc.update("INSERT IGNORE INTO reminder_log (borrowing_id, reminder_type, sent_on) VALUES (?, ?, ?)",
                        r.id(), k, Date.valueOf(today));
                if (inserted > 0) {
                    events.publishEvent(new DomainEvents.DueReminder(r.id(), k, Math.max(0, -daysUntil)));
                    return true;
                }
                return false;
            });
            if (Boolean.TRUE.equals(fired)) {
                sent++;
            }
        }
        return sent;
    }
}
