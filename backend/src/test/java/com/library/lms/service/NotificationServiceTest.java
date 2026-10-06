package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.library.lms.entity.Library;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.NotificationLog;
import com.library.lms.entity.NotificationOutcome;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.NotificationLogRepository;
import com.library.lms.repository.UserRepository;

/**
 * Who gets told, once, and what happens when the mail server will not have it.
 *
 * <p>The mailer is mocked, so these tests are about the rules around sending
 * rather than about the words sent - {@code NotificationMailerTest} covers those.
 * What is pinned here is the part that would be dangerous to get wrong.
 *
 * <p><b>The recipient is resolved from the database, not from the event.</b>
 * Several tests below hand the service an event naming an account that has since
 * been disabled, locked, deleted or left without an address, and require that
 * nothing is sent. An event is a request to consider telling somebody, not
 * permission to write to an address it carries - and it carries none.
 *
 * <p><b>Once means once.</b> The claim row is written before the message is
 * handed over, and a second attempt is refused by the unique key. A replayed
 * issue, a retried payment callback and a reminder sweep that runs twice are all
 * the same situation, and all of them produce one message.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long LIBRARY_ID = 7L;
    private static final Long RECIPIENT_ID = 30L;
    private static final Long LOAN_ID = 99L;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-29T09:00:00Z"), ZoneId.of("UTC"));

    @Mock
    private NotificationLogRepository notificationLog;

    @Mock
    private UserRepository userRepository;

    @Mock
    private LibraryRepository libraryRepository;

    @Mock
    private NotificationMailer mailer;

    private NotificationService service;

    @BeforeEach
    void fixtures() {
        service = new NotificationService(notificationLog, userRepository, libraryRepository, mailer, FIXED);
    }

    private User member(boolean enabled, boolean unlocked, String email) {
        Library library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Central");

        User user = new User();
        user.setId(RECIPIENT_ID);
        user.setUsername("asha");
        user.setFullName("Asha Verma");
        user.setEmail(email);
        user.setRole(Role.ROLE_MEMBER);
        user.setEnabled(enabled);
        user.setAccountNonLocked(unlocked);
        user.setLibrary(library);
        return user;
    }

    private Library namedLibrary() {
        Library library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Central");
        return library;
    }

    private User ableToReceive() {
        return member(true, true, "asha@example.invalid");
    }

    private NotificationRequested event(NotificationKind kind) {
        return new NotificationRequested(kind, LIBRARY_ID, RECIPIENT_ID, LOAN_ID);
    }

    /** The service claims, then sends. Both need stubbing for the happy path. */
    private void readyToSend() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(ableToReceive()));
        when(notificationLog.existsByKindAndSubjectIdAndRecipientUserId(any(), anyLong(), anyLong()))
                .thenReturn(false);
        when(libraryRepository.getReferenceById(LIBRARY_ID)).thenReturn(new Library());
        when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(namedLibrary()));
        when(notificationLog.save(any(NotificationLog.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ---------- 1. the happy path ----------

    @Test
    void aNotificationIsClaimedAndThenSent() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.SENT);

        service.deliver(event(NotificationKind.BOOK_ISSUED));

        // Twice: the claim, then the outcome. The second is an explicit save
        // rather than dirty checking, because this runs detached.
        ArgumentCaptor<NotificationLog> claim = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLog, org.mockito.Mockito.times(2)).save(claim.capture());

        assertThat(claim.getValue().getKind()).isEqualTo(NotificationKind.BOOK_ISSUED);
        assertThat(claim.getValue().getSubjectId()).isEqualTo(LOAN_ID);
        assertThat(claim.getValue().getRecipientUserId()).isEqualTo(RECIPIENT_ID);
        assertThat(claim.getValue().getCreatedAt()).isEqualTo(LocalDateTime.now(FIXED));

        verify(mailer).send(eq(NotificationKind.BOOK_ISSUED), any(User.class), any(), eq(LOAN_ID));
    }

    @Test
    void theClaimIsWrittenBeforeAnythingIsSent() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.SENT);

        service.deliver(event(NotificationKind.BOOK_ISSUED));

        // The order matters: the question the row answers is "have we already
        // decided to tell them?", not "did SMTP accept it?". Claiming afterwards
        // would let a slow send be claimed twice.
        var order = org.mockito.Mockito.inOrder(notificationLog, mailer);
        order.verify(notificationLog).save(any(NotificationLog.class));
        order.verify(mailer).send(any(), any(), any(), anyLong());
    }

    @Test
    void theOutcomeOfTheSendIsRecordedOnTheClaim() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.FAILED);

        service.deliver(event(NotificationKind.FINE_RECEIPT));

        ArgumentCaptor<NotificationLog> claim = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLog, org.mockito.Mockito.times(2)).save(claim.capture());

        // The second save carries the real outcome, so a failure leaves a row
        // saying so rather than the placeholder it was claimed with.
        assertThat(claim.getAllValues().get(1).getOutcome()).isEqualTo(NotificationOutcome.FAILED);
    }

    @Test
    void everyKindGoesThroughTheSameClaimAndSend() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.SENT);

        for (NotificationKind kind : NotificationKind.values()) {
            service.deliver(event(kind));
        }

        // No kind bypasses the claim: idempotency is a property of the mechanism
        // rather than of each event type remembering to use it.
        verify(notificationLog, org.mockito.Mockito.times(NotificationKind.values().length * 2))
                .save(any(NotificationLog.class));
    }

    // ---------- 2. the recipient is resolved server-side ----------

    @Test
    void anAccountThatNoLongerExistsIsNotWrittenTo() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.empty());

        service.deliver(event(NotificationKind.BOOK_ISSUED));

        verifyNoInteractions(mailer);
        verify(notificationLog, never()).save(any(NotificationLog.class));
    }

    @Test
    void aDisabledAccountIsNotWrittenTo() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(member(false, true, "a@b.invalid")));

        service.deliver(event(NotificationKind.REGISTRATION_APPROVED));

        verifyNoInteractions(mailer);
        verify(notificationLog, never()).save(any(NotificationLog.class));
    }

    @Test
    void aLockedAccountIsNotWrittenTo() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(member(true, false, "a@b.invalid")));

        service.deliver(event(NotificationKind.OVERDUE));

        verifyNoInteractions(mailer);
    }

    @Test
    void anAccountWithNoAddressIsNotWrittenTo() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(member(true, true, "  ")));

        service.deliver(event(NotificationKind.DUE_SOON));

        verifyNoInteractions(mailer);
    }

    @Test
    void theAddressComesFromTheAccountAndNeverFromTheEvent() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.SENT);

        service.deliver(event(NotificationKind.BOOK_ISSUED));

        // The event carries four ids and nothing else - there is no address on
        // it to use even by mistake. What the mailer receives is the account the
        // service just read.
        ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
        verify(mailer).send(any(), recipient.capture(), any(), anyLong());

        assertThat(recipient.getValue().getId()).isEqualTo(RECIPIENT_ID);
        assertThat(recipient.getValue().getEmail()).isEqualTo("asha@example.invalid");
    }

    @Test
    void anEventMustNameEverythingItNeeds() {
        // A notification with no recipient, no library or no subject cannot be
        // scoped or delivered, and is refused at construction rather than
        // becoming a null somewhere later.
        assertThatCode(() -> new NotificationRequested(null, LIBRARY_ID, RECIPIENT_ID, LOAN_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new NotificationRequested(NotificationKind.OVERDUE, null, RECIPIENT_ID, LOAN_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new NotificationRequested(NotificationKind.OVERDUE, LIBRARY_ID, null, LOAN_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new NotificationRequested(NotificationKind.OVERDUE, LIBRARY_ID, RECIPIENT_ID, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 3. once, and only once ----------

    @Test
    void somethingAlreadySentIsNotSentAgain() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(ableToReceive()));
        when(notificationLog.existsByKindAndSubjectIdAndRecipientUserId(
                NotificationKind.DUE_SOON, LOAN_ID, RECIPIENT_ID)).thenReturn(true);

        service.deliver(event(NotificationKind.DUE_SOON));

        verifyNoInteractions(mailer);
        verify(notificationLog, never()).save(any(NotificationLog.class));
    }

    @Test
    void losingTheRaceToClaimSendsNothingAndThrowsNothing() {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(ableToReceive()));
        when(notificationLog.existsByKindAndSubjectIdAndRecipientUserId(any(), anyLong(), anyLong()))
                .thenReturn(false);
        when(libraryRepository.getReferenceById(LIBRARY_ID)).thenReturn(new Library());
        when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(namedLibrary()));

        // Two threads both read false and both try to insert. The unique key
        // refuses the second, which is a normal outcome rather than an error.
        when(notificationLog.save(any(NotificationLog.class)))
                .thenThrow(new DataIntegrityViolationException("uk_notification_log_once"));

        assertThatCode(() -> service.deliver(event(NotificationKind.OVERDUE))).doesNotThrowAnyException();

        verifyNoInteractions(mailer);
    }

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    void theClaimIsCheckedForTheExactKindSubjectAndRecipient(NotificationKind kind) {
        when(userRepository.findById(RECIPIENT_ID)).thenReturn(Optional.of(ableToReceive()));
        when(notificationLog.existsByKindAndSubjectIdAndRecipientUserId(kind, LOAN_ID, RECIPIENT_ID))
                .thenReturn(true);

        service.deliver(event(kind));

        // Not "have we sent this kind before" and not "have we told this person
        // anything before": the three together are the key, so a member can be
        // told about two different loans and told two different things about one.
        verify(notificationLog).existsByKindAndSubjectIdAndRecipientUserId(kind, LOAN_ID, RECIPIENT_ID);
        verifyNoInteractions(mailer);
    }

    // ---------- 4. a failure to send changes nothing ----------

    @Test
    void theListenerSwallowsWhateverDeliveryThrows() {
        // By the time this runs the loan is committed and the caller answered.
        // Nothing here may escape: an exception from a listener after the answer
        // has gone has nowhere to be reported and no business meaning.
        when(userRepository.findById(RECIPIENT_ID)).thenThrow(new RuntimeException("database gone"));

        assertThatCode(() -> service.onNotificationRequested(event(NotificationKind.BOOK_ISSUED)))
                .doesNotThrowAnyException();
    }

    @Test
    void aMailerThatThrowsDoesNotEscapeTheListenerEither() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenThrow(new RuntimeException("smtp exploded"));

        assertThatCode(() -> service.onNotificationRequested(event(NotificationKind.FINE_RECEIPT)))
                .doesNotThrowAnyException();
    }

    @Test
    void theLibraryOnTheClaimIsTheOneTheEventNamed() {
        readyToSend();
        when(mailer.send(any(), any(), any(), anyLong())).thenReturn(NotificationOutcome.SENT);

        service.deliver(event(NotificationKind.BOOK_RETURNED));

        // The record belongs to the library the event happened in, which is what
        // keeps one library's notifications out of another's reads.
        verify(libraryRepository).getReferenceById(LIBRARY_ID);
    }
}
