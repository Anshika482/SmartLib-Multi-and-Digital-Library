package com.smartlib.circulation;

import java.time.Duration;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.catalogue.Book;
import com.smartlib.catalogue.BookCopy;
import com.smartlib.catalogue.BookCopyRepository;
import com.smartlib.catalogue.BookRepository;
import com.smartlib.catalogue.CopyStatus;
import com.smartlib.catalogue.HoldingStatus;
import com.smartlib.catalogue.LibraryHolding;
import com.smartlib.catalogue.LibraryHoldingRepository;
import com.smartlib.circulation.CirculationDtos.CreateRequest;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.util.Money;
import com.smartlib.common.util.Texts;
import com.smartlib.fine.FineRepository;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryPolicy;
import com.smartlib.library.LibraryPolicyRepository;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.RateLimiter;
import com.smartlib.security.SmartLibPrincipal;

/** Member-side borrow requests. The libraryId sent by the client is validated, never trusted. */
@Service
public class RequestService {

    private static final List<RequestStatus> OPEN = List.of(RequestStatus.PENDING, RequestStatus.APPROVED);

    private final LibraryRepository libraries;
    private final LibraryPolicyRepository policies;
    private final BookRepository books;
    private final LibraryHoldingRepository holdings;
    private final BookCopyRepository copies;
    private final BorrowRequestRepository requests;
    private final BorrowingRepository borrowings;
    private final FineRepository fines;
    private final MembershipService memberships;
    private final CirculationQueries queries;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final RateLimiter rateLimiter;

    public RequestService(LibraryRepository libraries, LibraryPolicyRepository policies, BookRepository books,
                          LibraryHoldingRepository holdings, BookCopyRepository copies, BorrowRequestRepository requests,
                          BorrowingRepository borrowings, FineRepository fines, MembershipService memberships,
                          CirculationQueries queries, AuditService audit, ApplicationEventPublisher events, RateLimiter rateLimiter) {
        this.libraries = libraries;
        this.policies = policies;
        this.books = books;
        this.holdings = holdings;
        this.copies = copies;
        this.requests = requests;
        this.borrowings = borrowings;
        this.fines = fines;
        this.memberships = memberships;
        this.queries = queries;
        this.audit = audit;
        this.events = events;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public RequestView create(SmartLibPrincipal member, CreateRequest req) {
        rateLimiter.check("request:" + member.userId(), 30, Duration.ofHours(1));
        Library library = libraries.findById(req.libraryId()).filter(Library::isActive)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "The selected library was not found."));
        Book book = books.findById(req.bookId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_FOUND, "The selected book was not found."));
        LibraryHolding holding = holdings.findByLibraryIdAndBookId(library.getId(), book.getId())
                .filter(h -> h.getStatus() == HoldingStatus.ACTIVE)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_IN_LIBRARY,
                        "\"" + book.getTitle() + "\" is not part of " + library.getName() + "'s catalogue."));
        memberships.ensureActive(member.userId(), library.getId());

        if (copies.countByHoldingIdAndStatus(holding.getId(), CopyStatus.AVAILABLE) == 0) {
            throw ApiException.conflict(ErrorCodes.BOOK_UNAVAILABLE,
                    "No copies of this book are currently available at " + library.getName() + ".");
        }
        if (requests.existsByMemberIdAndHoldingIdAndStatusIn(member.userId(), holding.getId(), OPEN)) {
            throw ApiException.conflict(ErrorCodes.REQUEST_ALREADY_EXISTS,
                    "You already have an open request for this book at " + library.getName() + ".");
        }
        if (borrowings.existsByMemberIdAndHoldingIdAndStatus(member.userId(), holding.getId(), BorrowingStatus.ACTIVE)) {
            throw ApiException.conflict(ErrorCodes.ALREADY_BORROWED, "You already have this book on loan from " + library.getName() + ".");
        }
        LibraryPolicy policy = policies.findById(library.getId()).orElse(LibraryPolicy.defaultsFor(library.getId()));
        long committed = borrowings.countByMemberIdAndLibraryIdAndStatus(member.userId(), library.getId(), BorrowingStatus.ACTIVE)
                + requests.countByMemberIdAndLibraryIdAndStatus(member.userId(), library.getId(), RequestStatus.APPROVED);
        if (committed >= policy.getMaxActiveLoans()) {
            throw ApiException.conflict(ErrorCodes.LOAN_LIMIT_REACHED,
                    "You have reached the limit of " + policy.getMaxActiveLoans() + " loans at " + library.getName() + ".");
        }
        if (requests.countByMemberIdAndLibraryIdAndStatus(member.userId(), library.getId(), RequestStatus.PENDING) >= policy.getMaxPendingRequests()) {
            throw ApiException.conflict(ErrorCodes.PENDING_LIMIT_REACHED,
                    "You can have at most " + policy.getMaxPendingRequests() + " pending requests at " + library.getName() + ".");
        }
        long unpaid = fines.sumUnpaid(member.userId(), library.getId());
        if (unpaid > policy.getBlockThresholdPaise()) {
            throw ApiException.conflict(ErrorCodes.OUTSTANDING_FINES,
                    "Please clear your outstanding fines of " + Money.inr(unpaid) + " at " + library.getName() + " before borrowing.")
                    .with("unpaidFinesPaise", unpaid);
        }

        BorrowRequest r = new BorrowRequest();
        r.setMemberId(member.userId());
        r.setLibraryId(holding.getLibraryId());
        r.setHoldingId(holding.getId());
        r.setBookId(book.getId());
        r.setStatus(RequestStatus.PENDING);
        r.setMemberNote(Texts.trimToNull(req.note()));
        try {
            requests.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict(ErrorCodes.REQUEST_ALREADY_EXISTS, "You already have an open request for this book here.");
        }
        audit.record(AuditActions.REQUEST_CREATED, "REQUEST", r.getId(), library.getId(),
                AuditService.details("bookId", book.getId(), "title", book.getTitle()));
        events.publishEvent(new DomainEvents.RequestCreated(r.getId()));
        return queries.memberRequests(member.userId(), null, null, 0, 1).content().stream()
                .filter(v -> v.id() == r.getId()).findFirst().orElseGet(() -> queries.request(r.getId()));
    }

    @Transactional
    public void cancel(SmartLibPrincipal member, long requestId) {
        BorrowRequest r = requests.lockByIdAndMemberId(requestId, member.userId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Request not found."));
        if (!OPEN.contains(r.getStatus())) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending or approved requests can be cancelled.");
        }
        if (r.getAssignedCopyId() != null) {
            copies.lockByIdAndLibraryId(r.getAssignedCopyId(), r.getLibraryId()).ifPresent(c -> release(c));
        }
        r.setStatus(RequestStatus.CANCELLED);
        requests.save(r);
        audit.record(AuditActions.REQUEST_CANCELLED, "REQUEST", r.getId(), r.getLibraryId(), null);
    }

    private void release(BookCopy c) {
        if (c.getStatus() == CopyStatus.RESERVED) {
            c.setStatus(CopyStatus.AVAILABLE);
            copies.save(c);
        }
    }
}
