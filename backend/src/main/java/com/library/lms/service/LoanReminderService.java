package com.library.lms.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.Transaction;
import com.library.lms.repository.TransactionRepository;

/**
 * Reminds people about books before and after they are due.
 *
 * <p><b>Nobody has to ask for this.</b> It runs on a timer for the whole
 * deployment, so no endpoint needs calling per member and no member of staff has
 * to remember. A background job has no library of its own, which is why it reads
 * across all of them - the notification it produces is still recorded against
 * the library the loan belongs to.
 *
 * <p><b>It cannot spam.</b> Every reminder goes through
 * {@link NotificationService}, whose unique key is the kind, the subject and the
 * recipient - so one DUE_SOON and one OVERDUE per loan, for ever, however many
 * times this sweep runs. The job is therefore safe to run hourly, to run twice
 * by accident, or to run again after a crash: the second pass claims nothing.
 * That is why there is no "last reminded" column here and no state of its own to
 * keep consistent.
 *
 * <p><b>Configurable, and off is a supported state.</b>
 * {@code notifications.reminders.enabled} stops it entirely - useful on a
 * developer's machine, and the honest setting for a deployment that has not
 * decided its wording yet. The threshold and the interval are properties too.
 *
 * <p><b>Bounded.</b> Each pass reads at most a page of loans, so a library that
 * has not run this for a year does not try to send forty thousand messages in
 * one transaction. The rest are picked up on the next pass, and nothing is lost
 * because the claim, not a cursor, is what decides whether a loan still needs
 * one.
 */
@Service
public class LoanReminderService {

    private static final Logger log = LoggerFactory.getLogger(LoanReminderService.class);

    static final String ENABLED_PROPERTY = "notifications.reminders.enabled";

    static final String INTERVAL_PROPERTY = "notifications.reminders.interval";

    /**
     * A minute before the first sweep.
     *
     * <p>Long enough that a start-up is not competing with it, short enough that
     * a deployment does not go a whole interval without reminders.</p>
     */
    private static final String INITIAL_DELAY = "PT1M";

    private final TransactionRepository transactionRepository;

    private final ApplicationEventPublisher events;

    private final OverduePolicy overduePolicy;

    private final boolean enabled;

    /** How many days ahead counts as "due soon". */
    private final int dueSoonDays;

    /** The most loans one pass will look at, of each kind. */
    private final int batchSize;

    public LoanReminderService(TransactionRepository transactionRepository,
            ApplicationEventPublisher events,
            OverduePolicy overduePolicy,
            @Value("${" + ENABLED_PROPERTY + ":true}") boolean enabled,
            @Value("${notifications.reminders.due-soon-days:3}") int dueSoonDays,
            @Value("${notifications.reminders.batch-size:200}") int batchSize) {
        this.transactionRepository = transactionRepository;
        this.events = events;
        this.overduePolicy = overduePolicy;
        this.enabled = enabled;
        this.dueSoonDays = dueSoonDays;
        this.batchSize = batchSize;
    }

    /**
     * One pass: what is due soon, and what is already late.
     *
     * <p>The interval is a property rather than a cron expression because the
     * job is idempotent and its exact timing does not matter - running it hourly
     * and running it daily differ in how soon somebody hears, not in what they
     * receive.</p>
     */
    @Scheduled(initialDelayString = INITIAL_DELAY, fixedDelayString = "${" + INTERVAL_PROPERTY + ":PT6H}")
    public void sweep() {
        if (!enabled) {
            return;
        }

        try {
            int dueSoon = remindDueSoon();
            int overdue = remindOverdue();

            if (dueSoon > 0 || overdue > 0) {
                log.info("Loan reminder sweep considered {} due soon and {} overdue", dueSoon, overdue);
            }
        } catch (RuntimeException failure) {
            // A scheduled method that throws is silently not rescheduled by some
            // configurations, and a reminder sweep that stops for ever after one
            // bad night is worse than one that logs and tries again.
            log.error("Loan reminder sweep failed ({})", failure.getClass().getSimpleName());
        }
    }

    /**
     * Loans due within the threshold, today included.
     *
     * @return how many were considered - not how many were sent, which only the
     *         notification claim knows
     */
    @Transactional(readOnly = true)
    public int remindDueSoon() {
        LocalDate today = overduePolicy.today();

        List<Transaction> loans = transactionRepository
                .findSystemWideByStatusInAndDueDateBetween(
                        OverduePolicy.OPEN_STATUSES, today, today.plusDays(dueSoonDays), page())
                .getContent();

        loans.forEach(loan -> publish(NotificationKind.DUE_SOON, loan));
        return loans.size();
    }

    /** Loans still out whose due date has passed. */
    @Transactional(readOnly = true)
    public int remindOverdue() {
        LocalDate today = overduePolicy.today();

        List<Transaction> loans = transactionRepository
                .findSystemWideByStatusInAndDueDateBefore(OverduePolicy.OPEN_STATUSES, today, page())
                .getContent();

        loans.forEach(loan -> publish(NotificationKind.OVERDUE, loan));
        return loans.size();
    }

    /** Oldest due date first, so the most pressing are reminded even when a pass is truncated. */
    private PageRequest page() {
        return PageRequest.of(0, batchSize, Sort.by(Sort.Direction.ASC, "dueDate"));
    }

    /**
     * Asks for one reminder.
     *
     * <p>Published rather than sent: whether it has already gone is
     * {@link NotificationService}'s to decide, and doing it here would be a
     * second copy of the rule. A loan with no borrower or no library is skipped
     * rather than guessed at.</p>
     */
    private void publish(NotificationKind kind, Transaction loan) {
        if (loan.getUser() == null || loan.getLibrary() == null) {
            return;
        }

        events.publishEvent(new NotificationRequested(
                kind, loan.getLibrary().getId(), loan.getUser().getId(), loan.getId()));
    }
}
