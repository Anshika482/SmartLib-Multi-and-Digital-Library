package com.smartlib.circulation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.catalogue.BookCopy;
import com.smartlib.catalogue.BookCopyRepository;
import com.smartlib.catalogue.CopyStatus;
import com.smartlib.circulation.CirculationDtos.LoanView;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.circulation.CirculationDtos.ReturnRequest;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.fine.Fine;
import com.smartlib.fine.FineService;
import com.smartlib.fine.FineType;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryPolicy;
import com.smartlib.library.LibraryPolicyRepository;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.LibraryScope;
import com.smartlib.user.LibraryMembershipRepository;
import com.smartlib.user.MembershipStatus;

/** Librarian workflow: review -> approve/reject -> issue -> return. Every lookup is scoped to the caller's library. */
@Service
public class CirculationService {

    private final BorrowRequestRepository requests;
    private final BorrowingRepository borrowings;
    private final BookCopyRepository copies;
    private final LibraryRepository libraries;
    private final LibraryPolicyRepository policies;
    private final LibraryMembershipRepository memberships;
    private final FineService fines;
    private final CirculationQueries queries;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppClock clock;

    public CirculationService(BorrowRequestRepository requests, BorrowingRepository borrowings, BookCopyRepository copies,
                              LibraryRepository libraries, LibraryPolicyRepository policies, LibraryMembershipRepository memberships,
                              FineService fines, CirculationQueries queries, AuditService audit, ApplicationEventPublisher events,
                              AppClock clock) {
        this.requests = requests;
        this.borrowings = borrowings;
        this.copies = copies;
        this.libraries = libraries;
        this.policies = policies;
        this.memberships = memberships;
        this.fines = fines;
        this.queries = queries;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record ReturnResult(LoanView loan, List<Long> fineIds, long finesTotalPaise) {
    }

    @Transactional
    public RequestView approve(LibraryScope scope, long requestId, Long copyId) {
        BorrowRequest r = scopedRequest(scope, requestId);
        if (r.getStatus() != RequestStatus.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending requests can be approved (current: " + r.getStatus() + ").");
        }
        BookCopy copy;
        if (copyId != null) {
            copy = copies.lockByIdAndLibraryId(copyId, scope.libraryId())
                    .filter(c -> c.getHoldingId().equals(r.getHoldingId()) && c.getStatus() == CopyStatus.AVAILABLE)
                    .orElseThrow(() -> ApiException.conflict(ErrorCodes.NO_COPY_AVAILABLE, "That copy is not available for this request."));
        } else {
            copy = copies.lockFirstAvailable(r.getHoldingId())
                    .orElseThrow(() -> ApiException.conflict(ErrorCodes.NO_COPY_AVAILABLE,
                            "No copy is available right now. You can reject the request or approve it later."));
        }
        Library library = libraries.findById(scope.libraryId()).orElseThrow();
        LibraryPolicy policy = policy(scope.libraryId());
        copy.setStatus(CopyStatus.RESERVED);
        copies.save(copy);
        r.setStatus(RequestStatus.APPROVED);
        r.setAssignedCopyId(copy.getId());
        r.setDecidedBy(scope.actorId());
        r.setDecidedAt(clock.now());
        r.setDecisionReason(null);
        r.setPickupDeadline(clock.today(library.getTimezone()).plusDays(policy.getPickupWindowDays()));
        requests.saveAndFlush(r);
        audit.record(AuditActions.REQUEST_APPROVED, "REQUEST", r.getId(), scope.libraryId(),
                AuditService.details("copy", copy.getAccessionNo(), "memberId", r.getMemberId()));
        events.publishEvent(new DomainEvents.RequestApproved(r.getId()));
        requests.flush();
        return queries.request(r.getId());
    }

    @Transactional
    public RequestView reject(LibraryScope scope, long requestId, String reason) {
        BorrowRequest r = scopedRequest(scope, requestId);
        if (r.getStatus() != RequestStatus.PENDING && r.getStatus() != RequestStatus.APPROVED) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only pending or approved requests can be rejected.");
        }
        releaseCopy(r, scope.libraryId());
        r.setStatus(RequestStatus.REJECTED);
        r.setDecidedBy(scope.actorId());
        r.setDecidedAt(clock.now());
        r.setDecisionReason(reason.trim());
        requests.saveAndFlush(r);
        audit.record(AuditActions.REQUEST_REJECTED, "REQUEST", r.getId(), scope.libraryId(), AuditService.details("reason", reason));
        events.publishEvent(new DomainEvents.RequestRejected(r.getId()));
        requests.flush();
        return queries.request(r.getId());
    }

    @Transactional
    public LoanView issue(LibraryScope scope, long requestId) {
        BorrowRequest r = scopedRequest(scope, requestId);
        if (r.getStatus() != RequestStatus.APPROVED) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Only approved requests can be issued.");
        }
        memberships.findByUserIdAndLibraryId(r.getMemberId(), scope.libraryId())
                .filter(m -> m.getStatus() == MembershipStatus.SUSPENDED)
                .ifPresent(m -> {
                    throw ApiException.forbidden(ErrorCodes.MEMBERSHIP_SUSPENDED, "This member's membership is suspended.");
                });
        BookCopy copy = copies.lockByIdAndLibraryId(r.getAssignedCopyId(), scope.libraryId())
                .filter(c -> c.getStatus() == CopyStatus.RESERVED)
                .orElseThrow(() -> ApiException.conflict(ErrorCodes.INVALID_STATE, "The reserved copy is no longer available."));
        Library library = libraries.findById(scope.libraryId()).orElseThrow();
        LibraryPolicy policy = policy(scope.libraryId());
        Borrowing b = new Borrowing();
        b.setRequestId(r.getId());
        b.setMemberId(r.getMemberId());
        b.setLibraryId(r.getLibraryId());
        b.setHoldingId(r.getHoldingId());
        b.setBookId(r.getBookId());
        b.setCopyId(copy.getId());
        b.setIssuedBy(scope.actorId());
        b.setIssuedAt(clock.now());
        b.setDueDate(clock.today(library.getTimezone()).plusDays(policy.getLoanPeriodDays()));
        b.setStatus(BorrowingStatus.ACTIVE);
        borrowings.saveAndFlush(b);
        copy.setStatus(CopyStatus.BORROWED);
        copies.save(copy);
        r.setStatus(RequestStatus.FULFILLED);
        requests.saveAndFlush(r);
        audit.record(AuditActions.BOOK_ISSUED, "BORROWING", b.getId(), scope.libraryId(),
                AuditService.details("requestId", r.getId(), "copy", copy.getAccessionNo(), "dueDate", b.getDueDate().toString()));
        events.publishEvent(new DomainEvents.BookIssued(b.getId()));
        requests.flush();
        return queries.loan(b.getId());
    }

    @Transactional
    public ReturnResult processReturn(LibraryScope scope, long borrowingId, ReturnRequest req) {
        Borrowing b = borrowings.lockByIdAndLibraryId(borrowingId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Loan not found."));
        if (b.getStatus() != BorrowingStatus.ACTIVE) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "This loan is already closed.");
        }
        Library library = libraries.findById(scope.libraryId()).orElseThrow();
        LibraryPolicy policy = policy(scope.libraryId());
        Instant now = clock.now();
        LocalDate returnDate = clock.today(library.getTimezone());
        BookCopy copy = copies.lockByIdAndLibraryId(b.getCopyId(), scope.libraryId()).orElseThrow();

        b.setReturnedAt(now);
        b.setReturnedTo(scope.actorId());
        b.setReturnCondition(req.condition());
        List<Long> fineIds = new ArrayList<>();
        long total = 0;
        String note = Texts.trimToNull(req.note());
        switch (req.condition()) {
            case GOOD -> {
                b.setStatus(BorrowingStatus.RETURNED);
                copy.setStatus(CopyStatus.AVAILABLE);
            }
            case DAMAGED -> {
                b.setStatus(BorrowingStatus.RETURNED);
                copy.setStatus(CopyStatus.DAMAGED);
                copy.setConditionNote(note);
            }
            case LOST -> {
                b.setStatus(BorrowingStatus.LOST);
                copy.setStatus(CopyStatus.LOST);
                copy.setConditionNote(note);
            }
        }
        borrowings.save(b);
        copies.saveAndFlush(copy);

        Fine overdue = fines.createOverdueFine(b, policy, returnDate, scope.actorId());
        if (overdue != null) {
            fineIds.add(overdue.getId());
            total += overdue.getAmountPaise();
        }
        if (req.condition() == ReturnCondition.DAMAGED && req.damageFeePaise() != null && req.damageFeePaise() > 0) {
            Fine f = fines.createForBorrowing(b, FineType.DAMAGED, req.damageFeePaise(), note == null ? "Damaged on return" : note, scope.actorId());
            fineIds.add(f.getId());
            total += f.getAmountPaise();
        }
        if (req.condition() == ReturnCondition.LOST && req.lostFeePaise() != null && req.lostFeePaise() > 0) {
            Fine f = fines.createForBorrowing(b, FineType.LOST, req.lostFeePaise(), note == null ? "Book reported lost" : note, scope.actorId());
            fineIds.add(f.getId());
            total += f.getAmountPaise();
        }
        audit.record(AuditActions.BOOK_RETURNED, "BORROWING", b.getId(), scope.libraryId(),
                AuditService.details("condition", req.condition().name(), "finesPaise", total));
        events.publishEvent(new DomainEvents.BookReturned(b.getId()));
        requests.flush();
        return new ReturnResult(queries.loan(b.getId()), fineIds, total);
    }

    /** Scheduled: approved requests whose pickup window passed release their reserved copy. */
    @Transactional
    public boolean expirePickup(long requestId) {
        BorrowRequest r = requests.lockById(requestId).orElse(null);
        if (r == null || r.getStatus() != RequestStatus.APPROVED) {
            return false;
        }
        releaseCopy(r, r.getLibraryId());
        r.setStatus(RequestStatus.EXPIRED);
        r.setDecisionReason("Not collected within the pickup window");
        requests.save(r);
        audit.recordAs(AuditService.Actor.system(), AuditActions.REQUEST_EXPIRED, "REQUEST", r.getId(), r.getLibraryId(), null);
        events.publishEvent(new DomainEvents.RequestExpired(r.getId()));
        return true;
    }

    private void releaseCopy(BorrowRequest r, long libraryId) {
        if (r.getAssignedCopyId() == null) {
            return;
        }
        copies.lockByIdAndLibraryId(r.getAssignedCopyId(), libraryId).ifPresent(c -> {
            if (c.getStatus() == CopyStatus.RESERVED) {
                c.setStatus(CopyStatus.AVAILABLE);
                copies.save(c);
            }
        });
    }

    private BorrowRequest scopedRequest(LibraryScope scope, long requestId) {
        return requests.lockByIdAndLibraryId(requestId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Request not found."));
    }

    private LibraryPolicy policy(long libraryId) {
        return policies.findById(libraryId).orElse(LibraryPolicy.defaultsFor(libraryId));
    }
}
