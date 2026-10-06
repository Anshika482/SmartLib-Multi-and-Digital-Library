package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.BorrowRequest;
import com.library.lms.entity.BorrowRequestStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.User;
import com.library.lms.exception.BorrowRequestNotAllowedException;
import com.library.lms.exception.BorrowRequestStateException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BorrowRequestRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * The parts of the workflow best proven with the collaborators held still.
 *
 * <p>{@code BorrowRequestWorkflowIntegrationTest} walks the whole thing through
 * HTTP against a real database, which is where the state machine and the
 * authorization rules are really settled. What is left for here is what mocks
 * are uniquely good at: proving that a call was <b>delegated</b> rather than
 * reimplemented, and that a refusal happened <b>before</b> anything was read.
 *
 * <p>The claim under test throughout: <b>this service does not know how to
 * issue a book.</b> It knows how to check a request and then ask
 * {@link TransactionService}. If that ever stops being true - if somebody adds
 * a decrement or an availability check here - these tests fail.
 */
@ExtendWith(MockitoExtension.class)
class BorrowRequestServiceTest {

    private static final Long LIBRARY_ID = 7L;
    private static final Long OTHER_LIBRARY_ID = 8L;
    private static final Long MEMBER_ID = 30L;
    private static final Long STAFF_ID = 31L;
    private static final Long BOOK_ID = 50L;
    private static final Long REQUEST_ID = 90L;
    private static final Long LOAN_ID = 99L;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-24T10:15:30Z"), ZoneId.of("UTC"));

    /**
     * Where notification events go.
     *
     * <p>Mocked because these tests are about the business rule, not about who
     * is told - that is {@code NotificationServiceTest}'s subject. Passing a
     * mock also demonstrates the point: this service only ever publishes, and
     * never sends anything itself.</p>
     */
    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private BorrowRequestRepository borrowRequestRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private AuditService auditService;

    private BorrowRequestService service;

    private Library library;

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Ours");

        service = new BorrowRequestService(borrowRequestRepository, bookRepository, userRepository,
                transactionRepository, transactionService, auditService, events, FIXED);
    }

    private User account(Long id, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername("caller");
        user.setFullName("A Caller");
        user.setRole(role);
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setLibrary(library);
        return user;
    }

    private void signedInAs(Role role) {
        Long id = role == Role.ROLE_MEMBER ? MEMBER_ID : STAFF_ID;
        when(userRepository.findByUsername("caller")).thenReturn(Optional.of(account(id, role)));
    }

    private Book book(int available) {
        Book book = new Book();
        book.setId(BOOK_ID);
        book.setTitle("A Title");
        book.setAuthor("An Author");
        book.setTotalCopies(3);
        book.setAvailableCopies(available);
        book.setLibrary(library);
        return book;
    }

    private BorrowRequest request(BorrowRequestStatus status) {
        BorrowRequest borrowRequest = new BorrowRequest();
        borrowRequest.setId(REQUEST_ID);
        borrowRequest.setBook(book(2));
        borrowRequest.setUser(account(MEMBER_ID, Role.ROLE_MEMBER));
        borrowRequest.setLibrary(library);
        borrowRequest.setStatus(status);
        return borrowRequest;
    }

    // ---------- issuing is delegated, not reimplemented ----------

    @Test
    void issuingAnApprovedRequestCallsTheExistingIssueService() {
        signedInAs(Role.ROLE_LIBRARIAN);
        BorrowRequest approved = request(BorrowRequestStatus.APPROVED);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(approved));

        LocalDate due = LocalDate.of(2026, 10, 8);
        TransactionResponse issued = new TransactionResponse();
        issued.setId(LOAN_ID);
        when(transactionService.issueBook(anyLong(), anyLong(), any(), any())).thenReturn(issued);

        Transaction loan = new Transaction();
        loan.setId(LOAN_ID);
        when(transactionRepository.findByIdAndLibraryId(LOAN_ID, LIBRARY_ID)).thenReturn(Optional.of(loan));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.issue(REQUEST_ID, due, "caller");

        // The book and the borrower come off the request; the staff member's own
        // name is passed through, which is what scopes the library inside.
        verify(transactionService).issueBook(eq(BOOK_ID), eq(MEMBER_ID), eq("caller"), eq(due));
    }

    @Test
    void issuingNeverTouchesStockItself() {
        signedInAs(Role.ROLE_LIBRARIAN);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(request(BorrowRequestStatus.APPROVED)));

        TransactionResponse issued = new TransactionResponse();
        issued.setId(LOAN_ID);
        when(transactionService.issueBook(anyLong(), anyLong(), any(), any())).thenReturn(issued);

        Transaction loan = new Transaction();
        loan.setId(LOAN_ID);
        when(transactionRepository.findByIdAndLibraryId(LOAN_ID, LIBRARY_ID)).thenReturn(Optional.of(loan));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.issue(REQUEST_ID, LocalDate.of(2026, 10, 8), "caller");

        // No save of a Book anywhere in this class. The decrement belongs to the
        // issue service and exists in exactly one place.
        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aRequestThatWasNotApprovedNeverReachesTheIssueService() {
        signedInAs(Role.ROLE_LIBRARIAN);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(request(BorrowRequestStatus.REQUESTED)));

        assertThatThrownBy(() -> service.issue(REQUEST_ID, LocalDate.of(2026, 10, 8), "caller"))
                .isInstanceOf(BorrowRequestStateException.class);

        verifyNoInteractions(transactionService);
    }

    @ParameterizedTest
    @EnumSource(value = BorrowRequestStatus.class,
            names = {"REQUESTED", "REJECTED", "CANCELLED", "FULFILLED"})
    void onlyAnApprovedRequestCanBeIssued(BorrowRequestStatus status) {
        signedInAs(Role.ROLE_LIBRARIAN);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(request(status)));

        assertThatThrownBy(() -> service.issue(REQUEST_ID, LocalDate.of(2026, 10, 8), "caller"))
                .isInstanceOf(BorrowRequestStateException.class)
                .hasMessageContaining(status.name());

        verifyNoInteractions(transactionService);
    }

    // ---------- approval moves no stock ----------

    @Test
    void approvingChecksAvailabilityWithoutReservingAnything() {
        signedInAs(Role.ROLE_LIBRARIAN);
        BorrowRequest waiting = request(BorrowRequestStatus.REQUESTED);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(waiting));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.approve(REQUEST_ID, "caller");

        assertThat(waiting.getStatus()).isEqualTo(BorrowRequestStatus.APPROVED);
        assertThat(waiting.getBook().getAvailableCopies()).as("approval must not reserve a copy").isEqualTo(2);
        verify(bookRepository, never()).save(any(Book.class));
        verifyNoInteractions(transactionService);
    }

    @Test
    void approvingRecordsWhoDecidedAndWhen() {
        signedInAs(Role.ROLE_LIBRARIAN);
        BorrowRequest waiting = request(BorrowRequestStatus.REQUESTED);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(waiting));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.approve(REQUEST_ID, "caller");

        assertThat(waiting.getDecidedByUserId()).isEqualTo(STAFF_ID);
        assertThat(waiting.getDecidedAt()).isEqualTo(java.time.LocalDateTime.now(FIXED));
    }

    @Test
    void cancellingNamesNobodyAsTheDecider() {
        signedInAs(Role.ROLE_MEMBER);
        BorrowRequest waiting = request(BorrowRequestStatus.REQUESTED);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(waiting));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.cancel(REQUEST_ID, "caller");

        assertThat(waiting.getStatus()).isEqualTo(BorrowRequestStatus.CANCELLED);
        // The member withdrew it themselves; naming them as the deciding member
        // of staff would be untrue.
        assertThat(waiting.getDecidedByUserId()).isNull();
    }

    // ---------- the role lock, independent of the filter chain ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ROLE_LIBRARIAN", "ROLE_ADMIN", "ROLE_SUPER_ADMIN"})
    void staffMayDecide(Role role) {
        signedInAs(role);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(request(BorrowRequestStatus.REQUESTED)));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        assertThat(service.approve(REQUEST_ID, "caller").status()).isEqualTo(BorrowRequestStatus.APPROVED);
    }

    @Test
    void aMemberIsRefusedByTheServiceBeforeAnythingIsRead() {
        signedInAs(Role.ROLE_MEMBER);

        // The filter chain already refuses this path. The service refuses it
        // again, and does so before reading a request - so the rule does not
        // rest on one line of configuration.
        assertThatThrownBy(() -> service.approve(REQUEST_ID, "caller"))
                .isInstanceOf(BorrowRequestNotAllowedException.class);

        verifyNoInteractions(borrowRequestRepository);
        verifyNoInteractions(transactionService);
    }

    // ---------- every read carries the caller's own library ----------

    @Test
    void everyLookupNamesTheCallersLibraryAndNeverAnother() {
        signedInAs(Role.ROLE_LIBRARIAN);
        when(borrowRequestRepository.findByIdAndLibraryId(REQUEST_ID, LIBRARY_ID))
                .thenReturn(Optional.of(request(BorrowRequestStatus.REQUESTED)));
        when(borrowRequestRepository.save(any(BorrowRequest.class))).thenAnswer(call -> call.getArgument(0));

        service.approve(REQUEST_ID, "caller");

        ArgumentCaptor<Long> libraryId = ArgumentCaptor.forClass(Long.class);
        verify(borrowRequestRepository).findByIdAndLibraryId(eq(REQUEST_ID), libraryId.capture());

        assertThat(libraryId.getValue()).isEqualTo(LIBRARY_ID);
        assertThat(libraryId.getValue()).isNotEqualTo(OTHER_LIBRARY_ID);
    }

    // ---------- paging is bounded, like every other list ----------

    @Test
    void aPageIsRefusedWhenItIsNegativeEmptyOrTooWide() {
        signedInAs(Role.ROLE_MEMBER);

        assertThatThrownBy(() -> service.mine(-1, 10, "requestedAt", "desc", "caller"))
                .isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> service.mine(0, 0, "requestedAt", "desc", "caller"))
                .isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> service.mine(0, 51, "requestedAt", "desc", "caller"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void onlyTheListedFieldsCanBeSortedOn() {
        signedInAs(Role.ROLE_MEMBER);

        assertThatThrownBy(() -> service.mine(0, 10, "user.password", "asc", "caller"))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("id, requestedAt, status");
    }
}
