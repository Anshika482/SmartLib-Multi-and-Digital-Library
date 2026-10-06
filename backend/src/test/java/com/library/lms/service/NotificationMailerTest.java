package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.NotificationOutcome;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.User;
import com.library.lms.repository.BorrowRequestRepository;
import com.library.lms.repository.TransactionRepository;

/**
 * What a notification actually says.
 *
 * <p><b>The messages are read, word for word.</b> That is the point of this
 * class: a notification is the one part of this application that leaves it and
 * cannot be recalled, so what it contains is worth asserting rather than
 * trusting. Every kind is checked for the things that must never be in an
 * inbox - a password, a token, a payment reference, an internal id - and the ones
 * that carry a figure are checked for having it right.
 *
 * <p>Built on the same arrangement {@code PasswordResetMailer} uses, so the
 * unconfigured case is tested here too: with no host there is no send and no
 * exception, which is what a development machine needs.
 */
class NotificationMailerTest {

    private static final Long LOAN_ID = 99L;
    private static final Long REQUEST_ID = 55L;

    private final JavaMailSender sender = mock(JavaMailSender.class);

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    private final BorrowRequestRepository borrowRequestRepository = mock(BorrowRequestRepository.class);

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider() {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(sender);
        return provider;
    }

    /** A mailer with somewhere to send from and to. */
    private NotificationMailer configured() {
        return new NotificationMailer(provider(), transactionRepository, borrowRequestRepository,
                "smtp.example.invalid", "library@example.invalid", "library@example.invalid");
    }

    /** A mailer on a machine with no mail server, which is the development default. */
    private NotificationMailer unconfigured() {
        return new NotificationMailer(provider(), transactionRepository, borrowRequestRepository,
                "", "", "");
    }

    private User member() {
        Library library = new Library();
        library.setId(7L);
        library.setName("Central Library");

        User user = new User();
        user.setId(30L);
        user.setUsername("asha");
        user.setFullName("Asha Verma");
        user.setEmail("asha@example.invalid");
        user.setRole(Role.ROLE_MEMBER);
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setLibrary(library);
        return user;
    }

    private Transaction loan(Double fine) {
        Book book = new Book();
        book.setId(50L);
        book.setTitle("The Left Hand of Darkness");

        Transaction transaction = new Transaction();
        transaction.setId(LOAN_ID);
        transaction.setBook(book);
        transaction.setDueDate(LocalDate.of(2026, 10, 12));
        transaction.setFineAmount(fine);
        return transaction;
    }

    private void loanExists(Double fine) {
        when(transactionRepository.findSystemWideById(LOAN_ID)).thenReturn(Optional.of(loan(fine)));
    }

    /**
     * The request's title, as the mailer now asks for it.
     *
     * <p>A projection rather than the entity: the mailer runs outside any
     * session, so reading a lazy {@code request.getBook()} would throw.</p>
     */
    private void requestExists() {
        when(borrowRequestRepository.findBookTitleById(REQUEST_ID)).thenReturn(Optional.of("Ubik"));
    }

    private SimpleMailMessage sentMessage() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    // ---------- 1. configuration ----------

    @Test
    void withNoMailServerNothingIsSentAndNothingThrows() {
        NotificationOutcome outcome = unconfigured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID);

        assertThat(outcome).isEqualTo(NotificationOutcome.NOT_CONFIGURED);
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void withAMailServerTheMessageIsSent() {
        loanExists(null);

        assertThat(configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID))
                .isEqualTo(NotificationOutcome.SENT);
    }

    @Test
    void aRefusalFromTheMailServerIsReportedRatherThanThrown() {
        loanExists(null);
        doThrow(new MailSendException("refused")).when(sender).send(any(SimpleMailMessage.class));

        // Reported, not thrown: by the time this runs the loan is committed and
        // the caller answered, so there is nowhere for an exception to go.
        assertThat(configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID))
                .isEqualTo(NotificationOutcome.FAILED);
    }

    @Test
    void aLoanThatHasGoneLeavesNothingTruthfulToSayAndIsNotInvented() {
        when(transactionRepository.findSystemWideById(LOAN_ID)).thenReturn(Optional.empty());

        assertThat(configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID))
                .isEqualTo(NotificationOutcome.FAILED);
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void theSenderIsTheConfiguredFromAddress() {
        loanExists(null);
        configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID);

        assertThat(sentMessage().getFrom()).isEqualTo("library@example.invalid");
    }

    @Test
    void theFromAddressFallsBackToTheSmtpAccount() {
        loanExists(null);

        NotificationMailer mailer = new NotificationMailer(provider(), transactionRepository,
                borrowRequestRepository, "smtp.example.invalid", "smtp-account@example.invalid", "");

        assertThat(mailer.send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID))
                .isEqualTo(NotificationOutcome.SENT);
        assertThat(sentMessage().getFrom()).isEqualTo("smtp-account@example.invalid");
    }

    // ---------- 2. what each kind says ----------

    @Test
    void anIssueNamesTheBookAndTheDueDate() {
        loanExists(null);
        configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID);

        SimpleMailMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo("A book has been issued to you");
        assertThat(message.getText()).contains("The Left Hand of Darkness");
        assertThat(message.getText()).contains("12 October 2026");
        assertThat(message.getText()).contains("Asha Verma");
    }

    @Test
    void aReturnMentionsAFineOnlyWhenThereIsOne() {
        loanExists(0.0);
        configured().send(NotificationKind.BOOK_RETURNED, member(), "Central Library", LOAN_ID);
        assertThat(sentMessage().getText()).doesNotContain("fine");
    }

    @Test
    void aLateReturnSaysWhatIsOwed() {
        loanExists(1.50);
        configured().send(NotificationKind.BOOK_RETURNED, member(), "Central Library", LOAN_ID);

        assertThat(sentMessage().getText()).contains("fine of 1.50");
    }

    @Test
    void anOverdueReminderSaysWhenItWasDue() {
        loanExists(null);
        configured().send(NotificationKind.OVERDUE, member(), "Central Library", LOAN_ID);

        assertThat(sentMessage().getSubject()).isEqualTo("A library book is overdue");
        assertThat(sentMessage().getText()).contains("12 October 2026").contains("overdue");
    }

    @Test
    void aDueSoonReminderIsWordedAsAWarningNotAnAccusation() {
        loanExists(null);
        configured().send(NotificationKind.DUE_SOON, member(), "Central Library", LOAN_ID);

        assertThat(sentMessage().getText()).contains("due back on 12 October 2026");
        assertThat(sentMessage().getText()).doesNotContain("overdue");
    }

    @Test
    void aReceiptSaysWhatWasPaidAndThatNoCardIsHeld() {
        loanExists(2.50);
        configured().send(NotificationKind.FINE_RECEIPT, member(), "Central Library", LOAN_ID);

        String text = sentMessage().getText();
        assertThat(text).contains("2.50");
        assertThat(text).contains("The Left Hand of Darkness");
        assertThat(text).contains("No card details are held");
    }

    @Test
    void aRequestDecisionNamesTheBookItWasAbout() {
        requestExists();
        configured().send(NotificationKind.REQUEST_APPROVED, member(), "Central Library", REQUEST_ID);

        assertThat(sentMessage().getText()).contains("Ubik").contains("ready to collect");
    }

    @Test
    void aRequestForABookThatHasGoneSaysNothing() {
        when(borrowRequestRepository.findBookTitleById(REQUEST_ID)).thenReturn(Optional.empty());

        assertThat(configured().send(NotificationKind.REQUEST_REJECTED, member(), "Central Library", REQUEST_ID))
                .isEqualTo(NotificationOutcome.FAILED);
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aMemberAndAMemberOfStaffAreToldDifferentThings() {
        configured().send(NotificationKind.REGISTRATION_APPROVED, member(), "Central Library", 30L);
        String asMember = sentMessage().getText();

        assertThat(asMember).contains("membership of Central Library");
        assertThat(asMember).doesNotContain("staff account");
    }

    @Test
    void anAccountWithNoNameIsGreetedByItsUsername() {
        loanExists(null);
        User nameless = member();
        nameless.setFullName("  ");

        configured().send(NotificationKind.BOOK_ISSUED, nameless, "Central Library", LOAN_ID);

        assertThat(sentMessage().getText()).contains("Hello asha,");
    }

    // ---------- 3. what no message may ever contain ----------

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    void noMessageCarriesACredentialAnIdOrALink(NotificationKind kind) {
        loanExists(2.50);
        requestExists();

        Long subject = switch (kind) {
            case REQUEST_APPROVED, REQUEST_REJECTED -> REQUEST_ID;
            default -> LOAN_ID;
        };

        assertThat(configured().send(kind, member(), "Central Library", subject)).isEqualTo(NotificationOutcome.SENT);

        SimpleMailMessage message = sentMessage();
        String whole = message.getSubject() + "\n" + message.getText();

        // Credentials and secrets of every kind.
        assertThat(whole).doesNotContainIgnoringCase("password");
        assertThat(whole).doesNotContainIgnoringCase("token");
        assertThat(whole).doesNotContainIgnoringCase("secret");
        assertThat(whole).doesNotContainIgnoringCase("cvv");
        assertThat(whole).doesNotContainIgnoringCase("card number");
        assertThat(whole).doesNotContainIgnoringCase("razorpay");
        assertThat(whole).doesNotContainIgnoringCase("signature");

        // No link, so there is no credential in a URL and nothing to phish.
        assertThat(whole).doesNotContain("http://");
        assertThat(whole).doesNotContain("https://");

        // No internal identifiers. The reader knows which book it is from its
        // title; an id is of no use to them and of some use to somebody else.
        assertThat(whole).doesNotContain(String.valueOf(LOAN_ID));
        assertThat(whole).doesNotContain(String.valueOf(REQUEST_ID));
        assertThat(whole).doesNotContain("libraryId");
        assertThat(whole).doesNotContain("userId");
    }

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    void everyKindHasItsOwnSubjectLine(NotificationKind kind) {
        loanExists(1.00);
        requestExists();

        Long subject = switch (kind) {
            case REQUEST_APPROVED, REQUEST_REJECTED -> REQUEST_ID;
            default -> LOAN_ID;
        };

        configured().send(kind, member(), "Central Library", subject);

        // Non-empty and not the enum name: a subject line reading
        // "STAFF_APPLICATION_REJECTED" is a leak of the schema into somebody's
        // inbox as well as being unreadable.
        String line = sentMessage().getSubject();
        assertThat(line).isNotBlank();
        assertThat(line).isNotEqualTo(kind.name());
        assertThat(line).doesNotContain("_");
    }

    @Test
    void onlyTheReadersOwnNameAppears() {
        loanExists(null);
        configured().send(NotificationKind.BOOK_ISSUED, member(), "Central Library", LOAN_ID);

        // A notification names one person: the reader. Nobody learns who else
        // borrowed what, or which member of staff decided anything.
        assertThat(sentMessage().getText()).contains("Asha Verma");
        assertThat(sentMessage().getText()).doesNotContain("librarian");
    }
}
