package com.library.lms.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.BorrowRequestResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.Book;
import com.library.lms.entity.BorrowRequest;
import com.library.lms.entity.BorrowRequestStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.BorrowRequestNotAllowedException;
import com.library.lms.exception.BorrowRequestNotFoundException;
import com.library.lms.exception.BorrowRequestStateException;
import com.library.lms.exception.DuplicateBorrowRequestException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.MemberNotEligibleException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BorrowRequestRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * The step before a loan: asking for a book, and what staff do about it.
 *
 * <p><b>This class does not issue books.</b> When an approved request is handed
 * over, it calls {@link TransactionService#issueBook}, which is the one place
 * that writes a loan and moves stock - unchanged by this feature and still
 * reachable on its own for a walk-up borrower who never made a request. There
 * is no second copy of the availability check, the decrement, or the rule about
 * who may borrow, because there is no second implementation of issuing.
 *
 * <p><b>Approval is not issue.</b> Approving records that staff agreed; it
 * moves no stock and writes no loan. A request therefore cannot make a book
 * unavailable to somebody standing at the desk, and an approval that is never
 * collected costs the shelf nothing. Availability is checked at approval too,
 * but as advice - the check that decides is the one inside the issue path, at
 * the moment the copy actually leaves.
 *
 * <p><b>Every query is scoped to the caller's own library</b>, taken from their
 * account, and a request belonging to another library is not found rather than
 * refused - the same answer an id that never existed gets, so the id cannot be
 * used to count a neighbour's queue.
 *
 * <p><b>Two locks on every rule.</b> The filter chain refuses a member on the
 * staff paths, and the methods below check the role again before reading
 * anything. Either alone would be enough; neither alone is trusted.
 */
@Service
public class BorrowRequestService {

    /** What a caller may order their requests by, mapped to the property actually sorted on. */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "requestedAt", "requestedAt",
            "status", "status");

    private static final int MAX_PAGE_SIZE = 50;

    private final BorrowRequestRepository borrowRequestRepository;

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    private final TransactionRepository transactionRepository;

    private final TransactionService transactionService;

    private final AuditService auditService;

    private final Clock clock;

    /**
     * The constructor Spring uses.
     *
     * <p>No {@code Clock} bean is published by this application, so the default
     * is supplied here and the package-private constructor below takes one -
     * the same arrangement {@link AuditService} uses, and for the same reason: a
     * test needs to fix the time without every caller having to pass a clock.</p>
     */

    /**
     * Where notification events go.
     *
     * <p>Published, never sent. What is delivered, to whom, and whether it has
     * already gone are {@code NotificationService}'s decisions - taken after
     * this service's transaction commits, so nothing here waits on a mail
     * server and no failure to send can undo what this service did.</p>
     */
    private final ApplicationEventPublisher events;

    @Autowired
    public BorrowRequestService(BorrowRequestRepository borrowRequestRepository, BookRepository bookRepository,
            UserRepository userRepository, TransactionRepository transactionRepository,
            TransactionService transactionService, AuditService auditService,
            ApplicationEventPublisher events) {
        this(borrowRequestRepository, bookRepository, userRepository, transactionRepository, transactionService,
                auditService, events, Clock.systemDefaultZone());
    }

    BorrowRequestService(BorrowRequestRepository borrowRequestRepository, BookRepository bookRepository,
            UserRepository userRepository, TransactionRepository transactionRepository,
            TransactionService transactionService, AuditService auditService,
            ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.borrowRequestRepository = borrowRequestRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ---------- the member's side ----------

    /**
     * A member asks for a book.
     *
     * <p>The book is resolved inside the caller's own library, so a book id
     * from another library is refused as missing and no request can cross a
     * tenant boundary. The member comes from the authenticated name and never
     * from the request body - a member id in the payload would be an invitation
     * to request a book on somebody else's behalf.
     *
     * <p>Nothing is reserved. The availability check here refuses a request for
     * a book with no copies on the shelf, which is a courtesy rather than a
     * guarantee: by the time staff approve it the position may have changed
     * either way, and the only check that decides is the one inside the issue
     * path.
     *
     * @throws BookNotFoundException          if the caller's library has no such book
     * @throws MemberNotEligibleException     if the caller is not a member in good standing
     * @throws DuplicateBorrowRequestException  if they already have a live request for it
     * @throws BookNotAvailableException      if the library holds no copy that could ever be lent
     */
    @Transactional
    public BorrowRequestResponse request(Long bookId, String authenticatedUsername) {
        User member = authenticatedUser(authenticatedUsername);
        Library library = member.getLibrary();
        Long libraryId = library.getId();

        // Staff issue at the desk; they do not queue for books. Checked before
        // anything is read, and answered the same way an ineligible member is,
        // so this endpoint cannot be used to learn what role an account holds.
        if (member.getRole() != Role.ROLE_MEMBER || !member.isEnabled() || !member.isAccountNonLocked()) {
            auditService.recordFailure(AuditAction.REQUEST_CREATED, libraryId, member.getId(), AuditTarget.none());
            throw new MemberNotEligibleException();
        }

        Book book = bookRepository.findByIdAndLibraryId(bookId, libraryId)
                .orElseThrow(() -> {
                    auditService.recordFailure(AuditAction.REQUEST_CREATED, libraryId, member.getId(),
                            AuditTarget.none());
                    return new BookNotFoundException(bookId);
                });

        // One live request per member per book. Asked of the database inside
        // this transaction rather than remembered, so two tabs cannot both pass
        // a check made before either wrote.
        if (borrowRequestRepository.existsByUserIdAndBookIdAndStatusInAndLibraryId(
                member.getId(), bookId, BorrowRequestStatus.ACTIVE, libraryId)) {
            auditService.recordFailure(AuditAction.REQUEST_CREATED, libraryId, member.getId(), AuditTarget.none());
            throw new DuplicateBorrowRequestException(book.getTitle());
        }

        // A book the library owns no copy of at all cannot be waited for. A
        // book whose copies are merely all on loan can: that is precisely what
        // a queue is for, and refusing it would make the feature useless on the
        // titles that most need it.
        Integer total = book.getTotalCopies();
        if (total == null || total <= 0) {
            auditService.recordFailure(AuditAction.REQUEST_CREATED, libraryId, member.getId(), AuditTarget.none());
            throw new BookNotAvailableException(bookId, book.getTitle());
        }

        BorrowRequest borrowRequest = new BorrowRequest();
        borrowRequest.setBook(book);
        borrowRequest.setUser(member);
        borrowRequest.setLibrary(library);
        borrowRequest.setStatus(BorrowRequestStatus.REQUESTED);
        borrowRequest.setRequestedAt(LocalDateTime.now(clock));

        BorrowRequest saved = borrowRequestRepository.save(borrowRequest);

        auditService.recordSuccess(AuditAction.REQUEST_CREATED, libraryId, member.getId(),
                AuditTarget.request(saved.getId()));

        return toResponse(saved, false);
    }

    /** One member's own requests. Never anybody else's, whatever id is asked for. */
    @Transactional(readOnly = true)
    public PagedResponse<BorrowRequestResponse> mine(int page, int size, String sortBy, String direction,
            String authenticatedUsername) {
        User member = authenticatedUser(authenticatedUsername);

        Page<BorrowRequest> requests = borrowRequestRepository.findByUserIdAndLibraryId(
                member.getId(), member.getLibrary().getId(), pageable(page, size, sortBy, direction));

        return toPagedResponse(requests, false);
    }

    /**
     * A member withdraws their own request.
     *
     * <p>Allowed while it is still active - waiting, or approved but not yet
     * collected. Once a copy has been handed over the loan is what matters and
     * cancelling would leave the book out with nothing recording why.
     *
     * @throws BorrowRequestNotFoundException   if the caller's library has no such request
     * @throws BorrowRequestNotAllowedException if it belongs to somebody else
     * @throws BorrowRequestStateException      if it has already been decided or issued
     */
    @Transactional
    public BorrowRequestResponse cancel(Long requestId, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Long libraryId = caller.getLibrary().getId();

        BorrowRequest borrowRequest = findInLibrary(requestId, libraryId, caller, AuditAction.REQUEST_CANCELLED);

        // Whose it is, checked after the library scope and before the state:
        // somebody else's request is not something this caller may read the
        // state of either.
        if (!borrowRequest.getUser().getId().equals(caller.getId())) {
            auditService.recordFailure(AuditAction.REQUEST_CANCELLED, libraryId, caller.getId(),
                    AuditTarget.request(requestId));
            throw new BorrowRequestNotAllowedException("You can only cancel your own request.");
        }

        requireActive(borrowRequest, "cancel", libraryId, caller, AuditAction.REQUEST_CANCELLED);

        borrowRequest.setStatus(BorrowRequestStatus.CANCELLED);
        borrowRequest.setDecidedAt(LocalDateTime.now(clock));
        // Deliberately not set: the member cancelled it themselves, and naming
        // them as the deciding member of staff would be untrue.
        borrowRequest.setDecidedByUserId(null);

        BorrowRequest cancelled = borrowRequestRepository.save(borrowRequest);

        auditService.recordSuccess(AuditAction.REQUEST_CANCELLED, libraryId, caller.getId(),
                AuditTarget.request(cancelled.getId()));

        return toResponse(cancelled, false);
    }

    // ---------- the desk's side ----------

    /**
     * A library's queue.
     *
     * <p>Defaults to what is waiting, because that is the working list. A
     * status may be named to review what was decided.
     */
    @Transactional(readOnly = true)
    public PagedResponse<BorrowRequestResponse> queue(BorrowRequestStatus status, int page, int size, String sortBy,
            String direction, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();
        Pageable pageable = pageable(page, size, sortBy, direction);

        Page<BorrowRequest> requests = status == null
                ? borrowRequestRepository.findByLibraryId(libraryId, pageable)
                : borrowRequestRepository.findByStatusAndLibraryId(status, libraryId, pageable);

        return toPagedResponse(requests, true);
    }

    /**
     * One request.
     *
     * <p>Staff may read any request in their library; a member may read their
     * own. A member asking for somebody else's gets the not-found answer rather
     * than a refusal, so the id tells them nothing.
     */
    @Transactional(readOnly = true)
    public BorrowRequestResponse getById(Long requestId, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        boolean staff = isStaff(caller);

        BorrowRequest borrowRequest = borrowRequestRepository
                .findByIdAndLibraryId(requestId, caller.getLibrary().getId())
                .orElseThrow(() -> new BorrowRequestNotFoundException(requestId));

        if (!staff && !borrowRequest.getUser().getId().equals(caller.getId())) {
            throw new BorrowRequestNotFoundException(requestId);
        }

        return toResponse(borrowRequest, staff);
    }

    /**
     * Staff agree to a request.
     *
     * <p>Availability is verified here, as the workflow requires, but nothing is
     * reserved and no stock moves: approving a request for the last copy does
     * not stop somebody else borrowing it first. That is deliberate. The
     * alternative - holding a copy from the moment of approval - is a different
     * product decision, and one that quietly makes books unavailable to the
     * people standing in the building.
     *
     * @throws BorrowRequestStateException if the request is not waiting
     * @throws BookNotAvailableException if no copy is on the shelf right now
     */
    @Transactional
    public BorrowRequestResponse approve(Long requestId, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();

        BorrowRequest borrowRequest = findInLibrary(requestId, libraryId, staff, AuditAction.REQUEST_APPROVED);

        // Only a waiting request can be agreed to. An approved one is already
        // agreed, and a terminal one is finished - both are refusals rather
        // than no-ops, so a stale screen is told it is stale.
        if (borrowRequest.getStatus() != BorrowRequestStatus.REQUESTED) {
            auditService.recordFailure(AuditAction.REQUEST_APPROVED, libraryId, staff.getId(),
                    AuditTarget.request(requestId));
            throw new BorrowRequestStateException("approve", borrowRequest.getStatus());
        }

        Book book = borrowRequest.getBook();
        Integer available = book.getAvailableCopies();
        if (available == null || available <= 0) {
            auditService.recordFailure(AuditAction.REQUEST_APPROVED, libraryId, staff.getId(),
                    AuditTarget.request(requestId));
            throw new BookNotAvailableException(book.getId(), book.getTitle());
        }

        borrowRequest.setStatus(BorrowRequestStatus.APPROVED);
        borrowRequest.setDecidedAt(LocalDateTime.now(clock));
        borrowRequest.setDecidedByUserId(staff.getId());

        BorrowRequest approved = borrowRequestRepository.save(borrowRequest);

        auditService.recordSuccess(AuditAction.REQUEST_APPROVED, libraryId, staff.getId(),
                AuditTarget.request(approved.getId()));

        // The member who asked is told, once this commits.
        events.publishEvent(new NotificationRequested(NotificationKind.REQUEST_APPROVED, libraryId,
                approved.getUser().getId(), approved.getId()));

        return toResponse(approved, true);
    }

    /**
     * Staff refuse a request.
     *
     * <p>Terminal, and says nothing about the book: a refusal is a decision
     * about this request, and the member may ask again.
     */
    @Transactional
    public BorrowRequestResponse reject(Long requestId, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();

        BorrowRequest borrowRequest = findInLibrary(requestId, libraryId, staff, AuditAction.REQUEST_REJECTED);

        // A waiting or an approved request may be refused - staff can change
        // their mind before the copy is handed over. A terminal one may not.
        if (!borrowRequest.getStatus().isActive()) {
            auditService.recordFailure(AuditAction.REQUEST_REJECTED, libraryId, staff.getId(),
                    AuditTarget.request(requestId));
            throw new BorrowRequestStateException("reject", borrowRequest.getStatus());
        }

        borrowRequest.setStatus(BorrowRequestStatus.REJECTED);
        borrowRequest.setDecidedAt(LocalDateTime.now(clock));
        borrowRequest.setDecidedByUserId(staff.getId());

        BorrowRequest rejected = borrowRequestRepository.save(borrowRequest);

        auditService.recordSuccess(AuditAction.REQUEST_REJECTED, libraryId, staff.getId(),
                AuditTarget.request(rejected.getId()));

        // The member who asked is told, once this commits.
        events.publishEvent(new NotificationRequested(NotificationKind.REQUEST_REJECTED, libraryId,
                rejected.getUser().getId(), rejected.getId()));

        return toResponse(rejected, true);
    }

    /**
     * Staff hand the copy over.
     *
     * <p><b>The loan is written by {@link TransactionService#issueBook}</b>, not
     * here. Everything that makes issuing safe - the availability check, the
     * decrement, the borrower's eligibility, the optimistic lock on the book
     * that makes two simultaneous issues of the last copy fail one of them, and
     * the BOOK_ISSUED audit event - happens there, exactly as it does for a
     * walk-up borrower. This method's own work is only to check that the
     * request was approved and to record which loan it became.
     *
     * <p>Both happen in one transaction, so a request can never be marked
     * fulfilled without the loan that fulfilled it, nor a loan written against
     * a request that still looks approved.
     *
     * @param dueDate when the book must come back, decided by the member of staff
     * @throws BorrowRequestStateException if the request was not approved
     */
    @Transactional
    public TransactionResponse issue(Long requestId, LocalDate dueDate, String authenticatedUsername) {
        User staff = requireStaff(authenticatedUsername);
        Long libraryId = staff.getLibrary().getId();

        BorrowRequest borrowRequest = findInLibrary(requestId, libraryId, staff, AuditAction.BOOK_ISSUED);

        // Approval and issue are separate states, so this is the one transition
        // that reads the earlier decision. A request nobody approved cannot be
        // handed over, and one already fulfilled cannot be handed over twice -
        // which is what stops a replayed call issuing a second copy.
        if (borrowRequest.getStatus() != BorrowRequestStatus.APPROVED) {
            auditService.recordFailure(AuditAction.BOOK_ISSUED, libraryId, staff.getId(),
                    AuditTarget.request(requestId));
            throw new BorrowRequestStateException("issue", borrowRequest.getStatus());
        }

        // The existing issue path, unchanged, with the book and borrower read
        // off the request rather than off the wire. It re-reads both within the
        // caller's library and refuses anything it does not like, so this call
        // is no more trusted than one arriving at /api/transactions/issue.
        TransactionResponse issued = transactionService.issueBook(
                borrowRequest.getBook().getId(),
                borrowRequest.getUser().getId(),
                authenticatedUsername,
                dueDate);

        Transaction loan = transactionRepository.findByIdAndLibraryId(issued.getId(), libraryId)
                .orElseThrow(() -> new TransactionNotFoundException(issued.getId()));

        borrowRequest.setStatus(BorrowRequestStatus.FULFILLED);
        borrowRequest.setTransaction(loan);
        borrowRequestRepository.save(borrowRequest);

        // No second audit event: issueBook already recorded BOOK_ISSUED against
        // the loan. Recording it twice would make the log count one hand-over
        // as two.
        return issued;
    }

    // ---------- helpers ----------

    /**
     * The caller, by the name the filter chain authenticated.
     *
     * <p>The one place this class turns a name into an account, so every method
     * resolves the caller the same way and none of them can be handed an id
     * instead.
     */
    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    /** The caller, refused unless they work here. The service half of the two locks. */
    private User requireStaff(String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);

        if (!isStaff(caller)) {
            throw new BorrowRequestNotAllowedException("Only library staff can act on requests.");
        }

        return caller;
    }

    private static boolean isStaff(User user) {
        return user.getRole() == Role.ROLE_LIBRARIAN
                || user.getRole() == Role.ROLE_ADMIN
                || user.getRole() == Role.ROLE_SUPER_ADMIN;
    }

    /** One request of the caller's library, recording the refusal when there is none. */
    private BorrowRequest findInLibrary(Long requestId, Long libraryId, User actor, AuditAction action) {
        return borrowRequestRepository.findByIdAndLibraryId(requestId, libraryId)
                .orElseThrow(() -> {
                    auditService.recordFailure(action, libraryId, actor.getId(), AuditTarget.request(requestId));
                    return new BorrowRequestNotFoundException(requestId);
                });
    }

    private void requireActive(BorrowRequest borrowRequest, String attempted, Long libraryId, User actor,
            AuditAction action) {
        if (!borrowRequest.getStatus().isActive()) {
            auditService.recordFailure(action, libraryId, actor.getId(), AuditTarget.request(borrowRequest.getId()));
            throw new BorrowRequestStateException(attempted, borrowRequest.getStatus());
        }
    }

    private Pageable pageable(int page, int size, String sortBy, String direction) {
        if (page < 0) {
            throw new InvalidPaginationException("Page must not be negative, but was " + page);
        }
        if (size <= 0) {
            throw new InvalidPaginationException("Size must be at least 1, but was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException("Size must not exceed " + MAX_PAGE_SIZE + ", but was " + size);
        }

        String property = SORTABLE_FIELDS.get(sortBy);
        if (property == null) {
            throw new InvalidSortException("Unsupported sort field. Allowed fields are: "
                    + String.join(", ", new TreeSet<>(SORTABLE_FIELDS.keySet())));
        }

        Sort.Direction order = "desc".equalsIgnoreCase(direction) ? Sort.Direction.DESC : Sort.Direction.ASC;

        return PageRequest.of(page, size, Sort.by(order, property));
    }

    /**
     * One row, as the API describes it.
     *
     * @param named whether the member's name may be shown - true for staff, who
     *              need to know whose request it is, and false on a member's own
     *              list, where the only name that could appear is their own
     */
    private BorrowRequestResponse toResponse(BorrowRequest borrowRequest, boolean named) {
        Book book = borrowRequest.getBook();
        Transaction loan = borrowRequest.getTransaction();

        return new BorrowRequestResponse(
                borrowRequest.getId(),
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                named ? borrowRequest.getUser().getFullName() : null,
                borrowRequest.getStatus(),
                borrowRequest.getRequestedAt(),
                borrowRequest.getDecidedAt(),
                loan == null ? null : loan.getId());
    }

    private PagedResponse<BorrowRequestResponse> toPagedResponse(Page<BorrowRequest> requests, boolean named) {
        List<BorrowRequestResponse> content = requests.getContent()
                .stream()
                .map(borrowRequest -> toResponse(borrowRequest, named))
                .toList();

        return new PagedResponse<>(content, requests.getNumber(), requests.getSize(),
                requests.getTotalElements(), requests.getTotalPages());
    }
}
