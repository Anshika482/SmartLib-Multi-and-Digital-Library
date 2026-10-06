package com.library.lms.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;

/**
 * Data-access layer for {@link Transaction}.
 *
 * <p>Every declared query is scoped to a library <b>except the eight named
 * {@code findSystemWide...}</b>, which answer across all of them and are
 * reachable only from the super administrator branch of
 * {@code TransactionService}. The rule and its one exception are both enforced
 * by {@code TransactionServiceLibraryScopingTest}, which pins the exception
 * list exactly.
 *
 * <p>The scoped ones are the point. The
 * unscoped versions these replace - {@code findByBookId}, {@code findByUserId}
 * and {@code findByStatus} - answered across every tenant at once, so a caller
 * in one library could read another library's loans just by asking. They were
 * removed rather than kept alongside these: a global method that still compiles
 * is a global method somebody will eventually call.</p>
 *
 * <p><b>Every finder that feeds a response fetches its book.</b> A loan is
 * described with its book's title, and {@code Transaction.book} is lazy while
 * {@code open-in-view} is false - so reading the title off a proxy after the
 * query has returned is a LazyInitializationException. Joining it in the query
 * also means a page of fifty loans is one statement rather than fifty-one.
 *
 * <p>The library is always the last query parameter and is always the caller's own,
 * resolved from the authenticated account by {@code TransactionService}. It is
 * never a value the client supplies.</p>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * One loan, but only if it belongs to this library.
     *
     * <p>Scoping the lookup rather than loading the row and checking afterwards
     * is what keeps the two failure cases identical, exactly as
     * {@link BookRepository#findByIdAndLibraryId} does: a loan in another
     * library and a loan that never existed both return empty, so a caller
     * cannot tell them apart - and the row is never read at all, which means a
     * refused return cannot act on data it was refused.</p>
     *
     * @param transactionId the loan wanted
     * @param libraryId     the caller's library
     * @return the loan, or empty if it is missing or belongs elsewhere
     */
    @EntityGraph(attributePaths = "book")
    Optional<Transaction> findByIdAndLibraryId(Long transactionId, Long libraryId);

    /**
     * One library's loans recorded against one book.
     *
     * <p>Reads as {@code findBy} + {@code BookId} + {@code AndLibraryId}, both
     * of which are foreign keys already on the transactions row, so no join is
     * needed. A book id belonging to another library matches nothing and gives
     * the same empty page as a book nobody has ever borrowed; the two answers
     * are meant to be indistinguishable.</p>
     *
     * <p>Returns a {@link Page} for the same reason the status query does:
     * scoping to one library bounds <i>whose</i> loans come back, not <i>how
     * many</i>, and a long-lived popular title accumulates history without
     * limit. The {@code LIMIT} is applied by the database, so rows beyond the
     * page are never materialised.</p>
     *
     * <p>The library stays in the query rather than the {@link Pageable} - it
     * is a predicate the caller cannot influence, while page, size and sort are
     * all things the caller supplies.</p>
     *
     * @param bookId    the book whose history is wanted
     * @param libraryId the caller's library
     * @param pageable  which slice to return, and in what order
     * @return one page of that library's loans against that book
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByBookIdAndLibraryId(Long bookId, Long libraryId, Pageable pageable);

    /**
     * One library's loans belonging to one user, resolved the same way via
     * {@code user_id}.
     *
     * <p>A user id from another library matches nothing, so a staff member
     * cannot read a neighbouring library's borrowing history by walking ids.</p>
     *
     * <p>Paged like its two siblings. A personal history is the most naturally
     * bounded of the three - one reader's activity rather than a whole
     * library's - but it still grows for as long as the account exists, and an
     * endpoint whose result size depends on how long somebody has been a member
     * is not bounded in any useful sense.</p>
     *
     * <p>The library stays in the query rather than the {@link Pageable}: it is
     * a predicate the caller cannot influence, while page, size and sort are
     * all things the caller supplies.</p>
     *
     * @param userId    the account whose history is wanted
     * @param libraryId the caller's library
     * @param pageable  which slice to return, and in what order
     * @return one page of that library's loans for that account
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByUserIdAndLibraryId(Long userId, Long libraryId, Pageable pageable);

    /**
     * One library's loans in one state.
     *
     * <p>The scoping matters most here. Unscoped, this was the widest hole in
     * the API: a single request for ISSUED returned every open loan in every
     * library at once. Taking the {@link TransactionStatus} enum rather than a
     * String also keeps a typo a compile error instead of a query that quietly
     * returns nothing.</p>
     *
     * <p>Returns a {@link Page} rather than a List, because scoping the query
     * to one library bounds <i>whose</i> loans come back but not <i>how
     * many</i>: a busy library's open loans grow without limit, and this is the
     * widest result the API can produce. The {@code LIMIT} is applied by the
     * database, so the rows are never materialised and then discarded.</p>
     *
     * <p>The library stays in the query, not in the {@link Pageable} - a
     * predicate a caller cannot influence, while page, size and sort are all
     * things a caller supplies.</p>
     *
     * @param status    the state to match
     * @param libraryId the caller's library
     * @param pageable  which slice to return, and in what order
     * @return one page of that library's loans in that state
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByStatusAndLibraryId(TransactionStatus status, Long libraryId,
                                               Pageable pageable);

    /**
     * One library's open loans whose due date has passed.
     *
     * <p>Overdue is not a stored state: a loan becomes overdue the day after its
     * due date without anything being written, so it is asked of the dates.
     * {@code statuses} names the states that mean a loan is still open, and
     * {@code date} is today, from the same clock the loans are then reported
     * by. Scoped by library like every query here, and paged like the other
     * lists.</p>
     *
     * @param statuses  the states in which a loan is still open
     * @param date      today; loans due strictly before it are overdue
     * @param libraryId the caller's library
     * @param pageable  which slice to return, and in what order
     * @return one page of that library's overdue loans
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByStatusInAndDueDateBeforeAndLibraryId(Collection<TransactionStatus> statuses,
                                                                  LocalDate date, Long libraryId,
                                                                  Pageable pageable);

    /**
     * One library's open loans that are not past their due date.
     *
     * <p>The other half of the query above: a loan due today is not yet overdue.
     * Together the two return every open loan exactly once.</p>
     *
     * @param statuses  the states in which a loan is still open
     * @param date      today; loans due on or after it are not overdue
     * @param libraryId the caller's library
     * @param pageable  which slice to return, and in what order
     * @return one page of that library's open loans that are not overdue
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByStatusInAndDueDateGreaterThanEqualAndLibraryId(Collection<TransactionStatus> statuses,
                                                                            LocalDate date, Long libraryId,
                                                                            Pageable pageable);

    /**
     * Reports whether this library has ever recorded a loan against this book.
     *
     * <p>Used before deleting a book. Reading it as Spring Data does:
     * {@code exists} + {@code ByBookId} + {@code AndLibraryId}, both of which
     * are foreign keys already on the transactions row, giving
     * {@code SELECT count(*) FROM transactions WHERE book_id = ? AND library_id = ?}
     * with no join - and the database can stop at the first match.</p>
     *
     * <p>{@code boolean} rather than a List because the caller only needs to
     * know <i>whether</i> history exists, never what it says. That also keeps
     * this method outside the disclosure question the scoped finders answer: it
     * returns one bit about a book the caller has already been shown to own,
     * never a row.</p>
     *
     * <p>It carries the library anyway, for two reasons. It keeps every query
     * on this interface scoped, so the rule needs no exceptions to remember;
     * and it is equivalent to the unscoped question in any case, because a
     * transaction always belongs to the same library as its book - the issue
     * path sets both from the caller's own account, so the two cannot diverge.
     * Should that ever fail to hold, the foreign key still refuses the delete,
     * so this check can only ever be more cautious than the database.</p>
     *
     * @param bookId    the book about to be deleted
     * @param libraryId the caller's library
     * @return true if any transaction, open or returned, references this book
     */
    boolean existsByBookIdAndLibraryId(Long bookId, Long libraryId);

    /**
     * One library's loans whose fine is in a given payment state.
     *
     * <p>Used for the fines screen, where the state wanted is UNPAID. That set
     * is exactly the payable one: a fine is only settled once the book is back,
     * so an open loan's fine - still growing by the day - is deliberately not
     * here. The accruing loans are listed by the overdue status query instead,
     * which asks the dates.</p>
     *
     * @param finePaymentStatus the state wanted, normally UNPAID
     * @param libraryId         the caller's library
     * @param pageable          which slice, and in what order
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByFinePaymentStatusAndLibraryId(FinePaymentStatus finePaymentStatus, Long libraryId,
                                                          Pageable pageable);

    /**
     * One member's own fines in a given payment state, within their library.
     *
     * <p>Scoped twice, by the member and by the library, so a member's fines
     * screen cannot be pointed at anybody else's.</p>
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findByUserIdAndFinePaymentStatusAndLibraryId(Long userId, FinePaymentStatus finePaymentStatus,
                                                                   Long libraryId, Pageable pageable);

    // ---------- system-wide, for the one role whose authority is ----------
    //
    // Every method below answers across every library, and each says so in its
    // name. That is the whole safeguard: the scoped versions above are what any
    // ordinary call reaches, and reaching one of these takes deliberately
    // typing "SystemWide", which no autocomplete offers by accident and no
    // reviewer can miss.
    //
    // TransactionService calls them from one branch only - seesEveryLibrary(),
    // true for ROLE_SUPER_ADMIN and nobody else - and the library still never
    // comes from a request either way. The scoping test pins this exact list,
    // so a seventh unscoped finder fails the build rather than appearing
    // quietly.

    /**
     * One loan, wherever it belongs.
     *
     * <p>Declared rather than reaching for the inherited {@code findById},
     * which returns the row with its book still a proxy - and a proxy read
     * after the query has returned is a LazyInitializationException, exactly as
     * for the scoped finders above. Naming it here also keeps every
     * cross-library read in one visible list.</p>
     */
    @EntityGraph(attributePaths = "book")
    Optional<Transaction> findSystemWideById(Long id);

    /** Every library's loans with a fine in this state. */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByFinePaymentStatus(FinePaymentStatus finePaymentStatus, Pageable pageable);

    /** One account's loans, wherever that account belongs. */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByUserId(Long userId, Pageable pageable);

    /** One book's loans, wherever the book belongs. */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByBookId(Long bookId, Pageable pageable);

    /** Every library's loans in one stored state. */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByStatus(TransactionStatus status, Pageable pageable);

    /** Every library's open loans past their due date. */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByStatusInAndDueDateBefore(Collection<TransactionStatus> statuses,
                                                               LocalDate date, Pageable pageable);

    /** Every library's open loans not yet past their due date. */
    /**
     * Every library's open loans due within a window.
     *
     * <p>For the reminder sweep, which runs for the whole deployment rather than
     * for one caller: a background job has no library, and scoping it to one
     * would mean somebody had to name which.</p>
     */
    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByStatusInAndDueDateBetween(Collection<TransactionStatus> statuses,
                                                               LocalDate from, LocalDate to, Pageable pageable);

    @EntityGraph(attributePaths = "book")
    Page<Transaction> findSystemWideByStatusInAndDueDateGreaterThanEqual(Collection<TransactionStatus> statuses,
                                                                         LocalDate date, Pageable pageable);

    // ---------- counts, for the dashboard ----------
    //
    // Every one takes a library id last, like every finder above, so a count is
    // scoped exactly the way a listing is. The member-scoped variants take a
    // user id as well: a member's dashboard must not be able to count anybody
    // else's loans.
    //
    // The two sums are JPQL rather than derived names, because Spring Data has
    // no derived form for SUM. They carry the library in their WHERE clause
    // instead, which the scoping test reads directly.

    /** Loans of one status in one library. */
    long countByStatusAndLibraryId(TransactionStatus status, Long libraryId);

    /** Loans still out and past their due date. */
    long countByStatusInAndDueDateBeforeAndLibraryId(Collection<TransactionStatus> statuses,
            LocalDate dueDate, Long libraryId);

    /** Loans whose fine has not been settled. */
    long countByFinePaymentStatusAndLibraryId(FinePaymentStatus finePaymentStatus, Long libraryId);

    /** One member's loans of one status, within their own library. */
    long countByUserIdAndStatusAndLibraryId(Long userId, TransactionStatus status, Long libraryId);

    /** One member's loans still out and past due. */
    long countByUserIdAndStatusInAndDueDateBeforeAndLibraryId(Long userId,
            Collection<TransactionStatus> statuses, LocalDate dueDate, Long libraryId);

    /** One member's unsettled fines. */
    long countByUserIdAndFinePaymentStatusAndLibraryId(Long userId,
            FinePaymentStatus finePaymentStatus, Long libraryId);

    /**
     * What one library is owed in unpaid fines.
     *
     * <p>Summed in the database rather than by reading rows: the figure is the
     * only thing wanted, and pulling every loan to add them up in Java would
     * carry member data into a method that has no use for it.</p>
     *
     * <p>COALESCE so a library owed nothing gets zero rather than null.</p>
     */
    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t"
            + " WHERE t.library.id = :libraryId AND t.finePaymentStatus = :status")
    double sumFines(@Param("libraryId") Long libraryId, @Param("status") FinePaymentStatus status);

    /** What one member owes, within their own library. */
    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t"
            + " WHERE t.library.id = :libraryId AND t.user.id = :userId AND t.finePaymentStatus = :status")
    double sumFinesForUser(@Param("libraryId") Long libraryId, @Param("userId") Long userId,
            @Param("status") FinePaymentStatus status);

}
