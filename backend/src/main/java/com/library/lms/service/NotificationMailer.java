package com.library.lms.service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.NotificationOutcome;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.User;
import com.library.lms.repository.BorrowRequestRepository;
import com.library.lms.repository.TransactionRepository;

/**
 * Turns a notification into a message, and hands it to the mail server.
 *
 * <p>Built on the same arrangement {@link PasswordResetMailer} uses, and for the
 * same reasons: an {@link ObjectProvider} for the sender so the application runs
 * with no mail server at all, {@code spring.mail.host} as the switch, and
 * {@code MAIL_FROM} or the SMTP account as the sender. There is one mail
 * configuration in this application and this is not a second one.
 *
 * <p><b>What a message may contain.</b> A title, a date, an amount, a library's
 * name. Never a password, a token, a reset link, a payment reference, a card
 * detail or an id that is not already the reader's own. The bodies below are the
 * whole of it - there is no template file and no interpolation of anything the
 * caller supplied.
 *
 * <p><b>No link into the application.</b> A notification says what happened and
 * leaves the reader to sign in themselves. A link with anything identifying in
 * it is a credential in an inbox, and a link without one is no more useful than
 * the sentence.
 *
 * <p><b>Never logged:</b> the address, the name, the book or the body. Log lines
 * name a kind and an account id.
 *
 * <p><b>Nothing here walks a lazy association.</b> This runs on a background
 * thread outside any session, so every value it needs is either a plain column
 * on an argument, a projection, or fetched by a query that joins what it reads.
 * The library's name is passed in for exactly that reason.
 */
@Component
public class NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private final ObjectProvider<JavaMailSender> mailSender;

    private final TransactionRepository transactionRepository;

    private final BorrowRequestRepository borrowRequestRepository;

    private final String host;

    private final String from;

    public NotificationMailer(ObjectProvider<JavaMailSender> mailSender,
            TransactionRepository transactionRepository,
            BorrowRequestRepository borrowRequestRepository,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.mail.from:}") String from) {
        this.mailSender = mailSender;
        this.transactionRepository = transactionRepository;
        this.borrowRequestRepository = borrowRequestRepository;
        this.host = host == null ? "" : host.trim();
        this.from = senderAddress(from, username);
    }

    /** {@code MAIL_FROM} when it is set, otherwise the SMTP account's own address. */
    private static String senderAddress(String from, String username) {
        String configured = from == null ? "" : from.trim();
        return !configured.isEmpty() ? configured : (username == null ? "" : username.trim());
    }

    /** Whether anything can actually be delivered: somewhere to send from and to. */
    boolean configured() {
        return !host.isEmpty() && !from.isEmpty();
    }

    /**
     * Sends one notification.
     *
     * @param kind      what happened
     * @param recipient the account to tell, already checked as able to receive
     * @param subjectId the loan, request or account it is about
     * @return whether it reached the mail server - never an exception
     */
    NotificationOutcome send(NotificationKind kind, User recipient, String libraryName, Long subjectId) {
        if (!configured()) {
            log.info("No delivery configured; notification {} for account id={} was not sent",
                    kind, recipient.getId());
            return NotificationOutcome.NOT_CONFIGURED;
        }

        Optional<String> body = body(kind, recipient, libraryName, subjectId);
        if (body.isEmpty()) {
            // The loan or request has gone, so there is nothing truthful to say
            // about it. Recorded rather than guessed at.
            log.info("Notification {} for subject id={} has nothing to describe", kind, subjectId);
            return NotificationOutcome.FAILED;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient.getEmail());
        message.setSubject(subject(kind));
        message.setText(body.get());

        try {
            mailSender.getObject().send(message);
            log.info("Notification {} sent for subject id={}", kind, subjectId);
            return NotificationOutcome.SENT;
        } catch (RuntimeException failure) {
            // The type only. An SMTP rejection quotes the address it rejected,
            // and often the message with it.
            log.error("Notification {} for subject id={} could not be sent ({})",
                    kind, subjectId, failure.getClass().getSimpleName());
            return NotificationOutcome.FAILED;
        }
    }

    private static String subject(NotificationKind kind) {
        return switch (kind) {
            case REGISTRATION_APPROVED -> "Your library membership is active";
            case REGISTRATION_REJECTED -> "About your library membership request";
            case STAFF_APPLICATION_APPROVED -> "Your library staff account is active";
            case STAFF_APPLICATION_REJECTED -> "About your library staff application";
            case REQUEST_APPROVED -> "Your book request is ready to collect";
            case REQUEST_REJECTED -> "About your book request";
            case BOOK_ISSUED -> "A book has been issued to you";
            case BOOK_RETURNED -> "Thank you for returning your book";
            case DUE_SOON -> "A library book is due back soon";
            case OVERDUE -> "A library book is overdue";
            case FINE_RECEIPT -> "Your library fine has been paid";
        };
    }

    /**
     * The body, or empty when the thing it describes has gone.
     *
     * <p>Each one says what happened and what to do, and nothing more. The
     * greeting uses the reader's own name; nobody else is named anywhere.</p>
     */
    private Optional<String> body(NotificationKind kind, User recipient, String libraryName, Long subjectId) {
        String hello = "Hello " + displayName(recipient) + ",\n\n";
        // Passed in, never walked to. recipient.getLibrary() is a lazy
        // association and this runs on a background thread outside any session,
        // so reading it here throws - and the only symptom would be that no
        // notification is ever sent.
        String library = libraryName == null || libraryName.isBlank() ? "your library" : libraryName;
        String signOff = "\n\nSign in to see the details.\n\n" + library;

        return switch (kind) {
            case REGISTRATION_APPROVED -> Optional.of(hello
                    + "Your membership of " + library + " has been approved. You can sign in and start"
                    + " borrowing." + signOff);

            case REGISTRATION_REJECTED -> Optional.of(hello
                    + "Your request to join " + library + " was not approved. The library can tell you more."
                    + "\n\n" + library);

            case STAFF_APPLICATION_APPROVED -> Optional.of(hello
                    + "Your staff account at " + library + " has been approved. You can sign in now."
                    + signOff);

            case STAFF_APPLICATION_REJECTED -> Optional.of(hello
                    + "Your application to work at " + library + " was not approved."
                    + "\n\n" + library);

            case REQUEST_APPROVED -> requestBody(subjectId).map(title -> hello
                    + "Your request for \"" + title + "\" has been approved. It is ready to collect at the"
                    + " desk." + signOff);

            case REQUEST_REJECTED -> requestBody(subjectId).map(title -> hello
                    + "Your request for \"" + title + "\" was not approved this time." + signOff);

            case BOOK_ISSUED -> loan(subjectId).map(loan -> hello
                    + "\"" + title(loan) + "\" has been issued to you.\n"
                    + "Please return it by " + loan.getDueDate().format(DAY) + "." + signOff);

            case BOOK_RETURNED -> loan(subjectId).map(loan -> hello
                    + "\"" + title(loan) + "\" has been returned. Thank you."
                    + fineNote(loan) + signOff);

            case DUE_SOON -> loan(subjectId).map(loan -> hello
                    + "\"" + title(loan) + "\" is due back on " + loan.getDueDate().format(DAY) + ".\n"
                    + "Returning it on time avoids a fine." + signOff);

            case OVERDUE -> loan(subjectId).map(loan -> hello
                    + "\"" + title(loan) + "\" was due back on " + loan.getDueDate().format(DAY)
                    + " and is now overdue.\nA fine is building up each day until it is returned."
                    + signOff);

            case FINE_RECEIPT -> loan(subjectId).map(loan -> hello
                    + "Your fine of " + amount(loan) + " for \"" + title(loan) + "\" has been paid and"
                    + " verified. Nothing further is owed on it.\n\n"
                    + "This is your receipt. No card details are held by the library." + signOff);
        };
    }

    /** The reader's own name, or their username when the library has no name for them. */
    private static String displayName(User recipient) {
        String name = recipient.getFullName();
        return name == null || name.isBlank() ? recipient.getUsername() : name;
    }

    private Optional<Transaction> loan(Long loanId) {
        return transactionRepository.findSystemWideById(loanId);
    }

    /** The title alone, projected - see {@code findBookTitleById}. */
    private Optional<String> requestBody(Long requestId) {
        return borrowRequestRepository.findBookTitleById(requestId);
    }

    private static String title(Transaction loan) {
        return loan.getBook() == null ? "A library book" : loan.getBook().getTitle();
    }

    /** What the return cost, when it cost anything. Silent when it did not. */
    private static String fineNote(Transaction loan) {
        Double fine = loan.getFineAmount();
        if (fine == null || fine <= 0) {
            return "";
        }
        return "\nIt came back late, so a fine of " + String.format(Locale.ENGLISH, "%.2f", fine)
                + " is owed.";
    }

    private static String amount(Transaction loan) {
        Double fine = loan.getFineAmount();
        return String.format(Locale.ENGLISH, "%.2f", fine == null ? 0.0 : fine);
    }
}
