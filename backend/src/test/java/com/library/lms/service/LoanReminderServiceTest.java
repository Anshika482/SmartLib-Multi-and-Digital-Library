package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.TransactionRepository;

/**
 * The reminder sweep: what it asks for, and what it refuses to do.
 *
 * <p><b>There is no "already reminded" state here, and that is the design.</b>
 * The sweep publishes a reminder for every loan it finds, every time it runs, and
 * {@link NotificationService} refuses the second one because the claim already
 * exists. So this class does not test "does it remember" - it tests that the
 * sweep is safe to run repeatedly, which is a different and much cheaper
 * property. Running it twice publishes the same events twice and sends one
 * message, and nothing here has to stay consistent across restarts.
 *
 * <p><b>Off is a supported state.</b> A disabled sweep must touch nothing at
 * all - not query, not publish - because the setting exists for deployments that
 * have not decided their wording and for developers who should not be emailing
 * anybody.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanReminderServiceTest {

    private static final Long LIBRARY_ID = 7L;
    private static final Long MEMBER_ID = 30L;

    /** A Monday, so the arithmetic below reads plainly. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 28);

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneId.of("UTC"));

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private ApplicationEventPublisher events;

    private final OverduePolicy policy = new OverduePolicy(new BigDecimal("0.50"), FIXED);

    private LoanReminderService service(boolean enabled, int dueSoonDays, int batchSize) {
        return new LoanReminderService(transactionRepository, events, policy, enabled, dueSoonDays, batchSize);
    }

    private LoanReminderService enabled() {
        return service(true, 3, 200);
    }

    private Transaction loan(Long id, LocalDate due) {
        Library library = new Library();
        library.setId(LIBRARY_ID);

        User borrower = new User();
        borrower.setId(MEMBER_ID);

        Book book = new Book();
        book.setId(50L);
        book.setTitle("A Title");

        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setLibrary(library);
        transaction.setUser(borrower);
        transaction.setBook(book);
        transaction.setDueDate(due);
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    private static Page<Transaction> page(Transaction... loans) {
        return new PageImpl<>(List.of(loans));
    }

    private List<NotificationRequested> published() {
        ArgumentCaptor<NotificationRequested> captor = ArgumentCaptor.forClass(NotificationRequested.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues();
    }

    // ---------- 1. off means off ----------

    @Test
    void aDisabledSweepQueriesNothingAndPublishesNothing() {
        service(false, 3, 200).sweep();

        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(events);
    }

    // ---------- 2. what it asks the database for ----------

    @Test
    void dueSoonAsksForLoansBetweenTodayAndTheThreshold() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page());

        service(true, 3, 200).remindDueSoon();

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository).findSystemWideByStatusInAndDueDateBetween(
                eq(OverduePolicy.OPEN_STATUSES), from.capture(), to.capture(), any(Pageable.class));

        // Today included: a book due today is due soon, and telling somebody on
        // the morning it is due is the most useful moment.
        assertThat(from.getValue()).isEqualTo(TODAY);
        assertThat(to.getValue()).isEqualTo(TODAY.plusDays(3));
    }

    @Test
    void theThresholdIsConfigurable() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page());

        service(true, 10, 200).remindDueSoon();

        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository).findSystemWideByStatusInAndDueDateBetween(
                any(), any(), to.capture(), any(Pageable.class));

        assertThat(to.getValue()).isEqualTo(TODAY.plusDays(10));
    }

    @Test
    void overdueAsksForOpenLoansDueBeforeToday() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBefore(any(), any(), any()))
                .thenReturn(page());

        enabled().remindOverdue();

        ArgumentCaptor<LocalDate> before = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository).findSystemWideByStatusInAndDueDateBefore(
                eq(OverduePolicy.OPEN_STATUSES), before.capture(), any(Pageable.class));

        // Strictly before today, which is the same rule the loan screens use: a
        // book due today is not late.
        assertThat(before.getValue()).isEqualTo(TODAY);
    }

    @Test
    void onlyOpenLoansAreConsidered() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBefore(any(), any(), any()))
                .thenReturn(page());

        enabled().remindOverdue();

        // A returned loan cannot be overdue, and reminding somebody about a book
        // they brought back is the worst kind of wrong.
        verify(transactionRepository).findSystemWideByStatusInAndDueDateBefore(
                eq(OverduePolicy.OPEN_STATUSES), any(), any(Pageable.class));
        assertThat(OverduePolicy.OPEN_STATUSES).doesNotContain(TransactionStatus.RETURNED);
    }

    @Test
    void eachPassIsBoundedAndTakesTheMostPressingFirst() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBefore(any(), any(), any()))
                .thenReturn(page());

        service(true, 3, 25).remindOverdue();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findSystemWideByStatusInAndDueDateBefore(
                any(), any(), pageable.capture());

        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
        // Oldest due date first, so a truncated pass still reminds the people
        // who have had the book longest.
        assertThat(pageable.getValue().getSort().getOrderFor("dueDate")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("dueDate").isAscending()).isTrue();
    }

    // ---------- 3. what it publishes ----------

    @Test
    void oneReminderIsPublishedPerLoanNamingTheBorrowerAndTheLibrary() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page(loan(101L, TODAY.plusDays(1)), loan(102L, TODAY.plusDays(2))));

        int considered = enabled().remindDueSoon();

        assertThat(considered).isEqualTo(2);

        List<NotificationRequested> requests = published();
        assertThat(requests).hasSize(2);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.kind()).isEqualTo(NotificationKind.DUE_SOON);
            assertThat(request.libraryId()).isEqualTo(LIBRARY_ID);
            // The borrower, decided server-side from the loan - the sweep has no
            // caller to take a recipient from.
            assertThat(request.recipientId()).isEqualTo(MEMBER_ID);
        });
        assertThat(requests).extracting(NotificationRequested::subjectId).containsExactly(101L, 102L);
    }

    @Test
    void anOverdueSweepPublishesTheOverdueKind() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBefore(any(), any(), any()))
                .thenReturn(page(loan(101L, TODAY.minusDays(4))));

        enabled().remindOverdue();

        assertThat(published()).singleElement()
                .satisfies(request -> assertThat(request.kind()).isEqualTo(NotificationKind.OVERDUE));
    }

    @Test
    void aLoanWithNoBorrowerOrNoLibraryIsSkippedRatherThanGuessedAt() {
        Transaction orphan = loan(101L, TODAY.plusDays(1));
        orphan.setUser(null);
        Transaction homeless = loan(102L, TODAY.plusDays(1));
        homeless.setLibrary(null);

        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page(orphan, homeless));

        enabled().remindDueSoon();

        verify(events, never()).publishEvent(any(NotificationRequested.class));
    }

    // ---------- 4. safe to run again ----------

    @Test
    void runningTwicePublishesTwiceAndRelicsOnTheClaimToSendOnce() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page(loan(101L, TODAY.plusDays(1))));

        LoanReminderService sweep = enabled();
        sweep.remindDueSoon();
        sweep.remindDueSoon();

        // Deliberately two events. The sweep keeps no state of its own - no
        // "last reminded" column to fall out of step with reality - and the
        // unique key on the notification decides that only one is sent. That is
        // what makes this job safe to run hourly, twice by accident, or again
        // after a crash.
        assertThat(published()).hasSize(2);
        assertThat(published()).allSatisfy(request ->
                assertThat(request.subjectId()).isEqualTo(101L));
    }

    @Test
    void aSweepThatFailsIsLoggedRatherThanLeftToStopTheSchedule() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("database gone"));

        // A scheduled method that throws can stop being rescheduled, and a
        // reminder sweep that gives up for ever after one bad night is worse
        // than one that logs and tries again.
        assertThatCode(() -> enabled().sweep()).doesNotThrowAnyException();
    }

    @Test
    void aFullSweepDoesBothHalves() {
        when(transactionRepository.findSystemWideByStatusInAndDueDateBetween(any(), any(), any(), any()))
                .thenReturn(page(loan(101L, TODAY.plusDays(1))));
        when(transactionRepository.findSystemWideByStatusInAndDueDateBefore(any(), any(), any()))
                .thenReturn(page(loan(202L, TODAY.minusDays(2))));

        enabled().sweep();

        assertThat(published()).extracting(NotificationRequested::kind)
                .containsExactlyInAnyOrder(NotificationKind.DUE_SOON, NotificationKind.OVERDUE);
    }
}
