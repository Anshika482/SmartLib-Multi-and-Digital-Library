package com.library.lms.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.library.lms.entity.NotificationLog;
import com.library.lms.entity.NotificationOutcome;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.NotificationLogRepository;
import com.library.lms.repository.UserRepository;

/**
 * Tells people what happened, once each.
 *
 * <p><b>After commit, and on another thread.</b> The listener below runs only
 * once the business transaction has committed, so nobody is told about a loan
 * that was rolled back; and it runs asynchronously, so the member of staff who
 * pressed "issue" is not kept waiting on an SMTP handshake. A request's response
 * time does not depend on a mail server being awake.
 *
 * <p><b>A failed send changes nothing else.</b> By the time this runs the loan,
 * the payment or the registration is committed and the caller has been answered.
 * Every failure is caught and recorded; none is rethrown. There is no path by
 * which an unreachable mail server can undo a book that was handed over.
 *
 * <p><b>Once, and only once.</b> The claim is a row in {@code notification_log}
 * whose unique key is the kind, the subject and the recipient. A second attempt
 * to tell the same person the same thing about the same loan fails to insert and
 * is skipped - so retrying an issue, replaying a payment callback, or running
 * the reminder sweep twice in a day all produce one message. The claim is made
 * <i>before</i> the message is sent, because the question is "have we already
 * decided to tell them?" rather than "did SMTP accept it?".
 *
 * <p><b>Nobody disabled is written to.</b> The account is read at the moment of
 * sending rather than trusted from the event, so somebody disabled, locked or
 * deleted between the event and the send gets nothing - and neither does an
 * account with no address.
 *
 * <p><b>Never logged:</b> the address, the name, the book, or anything else the
 * message carries. Log lines name a kind, a subject id and an account id.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository notificationLog;

    private final UserRepository userRepository;

    private final LibraryRepository libraryRepository;

    private final NotificationMailer mailer;

    private final Clock clock;

    @Autowired
    public NotificationService(NotificationLogRepository notificationLog, UserRepository userRepository,
            LibraryRepository libraryRepository, NotificationMailer mailer) {
        this(notificationLog, userRepository, libraryRepository, mailer, Clock.systemDefaultZone());
    }

    NotificationService(NotificationLogRepository notificationLog, UserRepository userRepository,
            LibraryRepository libraryRepository, NotificationMailer mailer, Clock clock) {
        this.notificationLog = notificationLog;
        this.userRepository = userRepository;
        this.libraryRepository = libraryRepository;
        this.mailer = mailer;
        this.clock = clock;
    }

    /**
     * Handles one notification, after the thing it describes is committed.
     *
     * <p>{@code @Async} on a {@code @TransactionalEventListener} is the
     * combination that matters: the phase keeps it after the commit, and the
     * async keeps it off the request thread. Without the phase it could describe
     * a rolled-back loan; without the async every issue would wait on SMTP.</p>
     *
     * <p>{@code fallbackExecution} is what makes the reminder sweep work. An
     * AFTER_COMMIT listener is <b>silently dropped</b> when the publisher is not
     * in a transaction, and the sweep is a background job with no business
     * transaction to commit - so without this, reminders would simply never be
     * delivered and nothing would say why. Business events are unaffected: they
     * are published inside a transaction and still wait for it.</p>
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationRequested(NotificationRequested event) {
        try {
            deliver(event);
        } catch (RuntimeException failure) {
            // Nothing may escape a listener running after the answer has gone.
            // The type only: a mail failure quotes the address it rejected.
            log.error("Notification {} for subject id={} could not be handled ({})",
                    event.kind(), event.subjectId(), failure.getClass().getSimpleName());
        }
    }

    /**
     * Claims the notification and sends it, in a transaction of its own.
     *
     * <p>{@code REQUIRES_NEW} because the business transaction is already
     * committed and gone by the time this runs - there is nothing to join. It
     * applies when this method is called directly; the listener above reaches it
     * by self-invocation, so there the two writes below simply commit
     * separately. Correct either way, which is why nothing here depends on
     * dirty checking: the claim is the part that must not be lost, and the
     * outcome is written with a second explicit save.</p>
     *
     * <p>The claim committing before the send is deliberate. It is what stops a
     * failing mail server turning into a retry loop that mails somebody eleven
     * times once it recovers.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(NotificationRequested event) {
        // Read now rather than trusted from the event: somebody disabled between
        // the event and this moment gets nothing.
        Optional<User> recipient = userRepository.findById(event.recipientId())
                .filter(User::isEnabled)
                .filter(User::isAccountNonLocked)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank());

        if (recipient.isEmpty()) {
            log.info("Notification {} for subject id={} not sent: account id={} cannot receive it",
                    event.kind(), event.subjectId(), event.recipientId());
            return;
        }

        // The cheap check first. It is not the guarantee - two threads could
        // both read false - which is why the insert below is allowed to fail.
        if (notificationLog.existsByKindAndSubjectIdAndRecipientUserId(
                event.kind(), event.subjectId(), event.recipientId())) {
            log.debug("Notification {} for subject id={} already sent to account id={}",
                    event.kind(), event.subjectId(), event.recipientId());
            return;
        }

        // Read rather than referenced: getReferenceById gives a proxy, and the
        // mailer needs the name - which a detached proxy cannot produce. Absent
        // is survivable, so the message says "your library" instead.
        String libraryName = libraryRepository.findById(event.libraryId())
                .map(library -> library.getName())
                .orElse(null);

        NotificationLog claim = new NotificationLog();
        claim.setLibrary(libraryRepository.getReferenceById(event.libraryId()));
        claim.setRecipientUserId(event.recipientId());
        claim.setKind(event.kind());
        claim.setSubjectId(event.subjectId());
        claim.setCreatedAt(LocalDateTime.now(clock));
        claim.setOutcome(NotificationOutcome.NOT_CONFIGURED);

        NotificationLog claimed;
        try {
            claimed = notificationLog.save(claim);
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // The unique key did its job: somebody else got there first. A
            // normal outcome, not an error.
            log.debug("Notification {} for subject id={} was already claimed", event.kind(), event.subjectId());
            return;
        }

        // Only now is anything sent - the claim above is already committed, so a
        // send that fails leaves a row saying so rather than leaving room for a
        // second attempt.
        NotificationOutcome outcome = mailer.send(
                event.kind(), recipient.get(), libraryName, event.subjectId());

        // Saved explicitly rather than left to dirty checking. This method is
        // reached by self-invocation from the listener, so the @Transactional
        // below does not apply there and the claim is already committed and
        // detached - a field set on it would simply be lost, and the log would
        // record the placeholder for ever instead of what actually happened.
        claimed.setOutcome(outcome);
        notificationLog.save(claimed);
    }
}
