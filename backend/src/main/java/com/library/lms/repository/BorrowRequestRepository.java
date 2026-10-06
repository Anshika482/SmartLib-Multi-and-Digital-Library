package com.library.lms.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.BorrowRequest;
import com.library.lms.entity.BorrowRequestStatus;

/**
 * Data-access layer for {@link BorrowRequest}.
 *
 * <p>Every declared query carries the library, and the library is always the
 * last parameter - the same rule {@link TransactionRepository} follows, and for
 * the same reason: a global method that still compiles is a global method
 * somebody will eventually call. There is no unscoped finder here to be tempted
 * by, not even for the duplicate check, which is scoped by the member and their
 * library both.
 *
 * <p>The library is always the caller's own, resolved from the authenticated
 * account by {@code BorrowRequestService}. It is never a value a client supplies.
 */
@Repository
public interface BorrowRequestRepository extends JpaRepository<BorrowRequest, Long> {

    /**
     * One request, but only if it belongs to this library.
     *
     * <p>Scoped rather than loaded and checked afterwards, exactly as
     * {@link BookRepository#findByIdAndLibraryId} is: a request in another
     * library and a request that never existed both arrive as an empty
     * Optional, so a caller cannot tell them apart and the row is never read at
     * all.</p>
     */
    Optional<BorrowRequest> findByIdAndLibraryId(Long id, Long libraryId);

    /** One member's own requests, newest first by the caller's page order. */
    Page<BorrowRequest> findByUserIdAndLibraryId(Long userId, Long libraryId, Pageable pageable);

    /** A library's queue, in one state. */
    Page<BorrowRequest> findByStatusAndLibraryId(BorrowRequestStatus status, Long libraryId, Pageable pageable);

    /** A library's whole queue, whatever state each request is in. */
    Page<BorrowRequest> findByLibraryId(Long libraryId, Pageable pageable);

    /**
     * Whether this member already has a live request for this book.
     *
     * <p>The duplicate rule, asked as one question. {@code statuses} is
     * {@link BorrowRequestStatus#ACTIVE}, so a member whose earlier request was
     * rejected, withdrawn or already issued may ask again - which is the point
     * of the rule being about active requests rather than about any request
     * ever made.</p>
     */
    boolean existsByUserIdAndBookIdAndStatusInAndLibraryId(Long userId, Long bookId,
            Collection<BorrowRequestStatus> statuses, Long libraryId);

    /**
     * The title of the book one request is for, and nothing else.
     *
     * <p>A projection rather than the entity, because the caller is the notification
     * mailer and it runs outside any session: reading {@code request.getBook().getTitle()}
     * off a detached row is a LazyInitializationException, and one that would show up
     * as "no notifications are ever sent" rather than as an error anybody notices.</p>
     */
    @Query("SELECT r.book.title FROM BorrowRequest r WHERE r.id = :id")
    Optional<String> findBookTitleById(@Param("id") Long id);

    /** How many of a library's requests are in one state. For the dashboard and the queue badge. */
    long countByStatusAndLibraryId(BorrowRequestStatus status, Long libraryId);
}
