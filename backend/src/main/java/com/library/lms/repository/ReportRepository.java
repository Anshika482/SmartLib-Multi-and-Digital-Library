package com.library.lms.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;

/**
 * The aggregates behind the reports, counted by the database.
 *
 * <p><b>Every figure here is one query.</b> Nothing walks a list of loans in
 * Java to add something up: a report over a year of borrowing touches as many
 * rows as a report over a day, and the row count never reaches the application
 * at all. That is the difference between a report and a memory problem.
 *
 * <p><b>Two families, and the names say which.</b> Everything ending
 * {@code ...AndLibraryId} answers for one library and takes it as its last
 * parameter, exactly as {@link TransactionRepository}'s finders do. Everything
 * ending {@code ...SystemWide} answers across every library and is reachable
 * only from the super administrator branch of {@code ReportService}. There is
 * no method that could be either depending on a null, because a report that
 * quietly widened its own scope is the failure this whole arrangement exists to
 * prevent. {@code ReportRepositoryScopingTest} pins both lists exactly.
 *
 * <p>Separate from {@link TransactionRepository} deliberately. That interface
 * serves loans to people and its scoping guard is about exactly that; these are
 * read-only aggregates that answer nobody's request for a row, and mixing the
 * two would blur a boundary worth keeping sharp.
 *
 * <p>Extends {@code Repository} rather than {@code JpaRepository}, so there is
 * no inherited {@code findAll} or {@code save} here at all - this interface can
 * only count.
 */
@org.springframework.stereotype.Repository
public interface ReportRepository extends Repository<Transaction, Long> {

    // ---------- projections ----------

    /** One book and how often it went out. */
    interface TitleCount {
        String getTitle();

        String getAuthor();

        long getIssues();
    }

    /** One shelf and how often anything on it went out. */
    interface CategoryCount {
        String getCategory();

        long getIssues();
    }

    /** One month, and how much happened in it. */
    interface MonthCount {
        int getYear();

        int getMonth();

        long getTotal();
    }

    // ---------- one library ----------

    /**
     * Loans issued within the range, inclusive of both ends.
     *
     * <p>Counted on {@code issueDate}, which is the day the copy left the
     * shelf - so a loan issued in the range and returned long after it is still
     * this range's issue.</p>
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.issueDate BETWEEN :from AND :to")
    long countIssuesAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    /** Loans returned within the range, counted on the day they came back. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.returnDate BETWEEN :from AND :to")
    long countReturnsAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    /**
     * Loans still out, whenever they were issued.
     *
     * <p>Deliberately not date-ranged: "how many books are out" is a fact about
     * now, and answering it for a window in the past would be a different and
     * much stranger question.</p>
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.library.id = :libraryId AND t.status IN :open")
    long countActiveLoansAndLibraryId(@Param("open") Collection<TransactionStatus> open,
            @Param("libraryId") Long libraryId);

    /** Loans still out whose due date has passed - the same rule the loan screens use. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.status IN :open AND t.dueDate < :today")
    long countOverdueAndLibraryId(@Param("open") Collection<TransactionStatus> open,
            @Param("today") LocalDate today, @Param("libraryId") Long libraryId);

    /**
     * Fines raised on loans returned within the range.
     *
     * <p>Counted on the return date, because that is when a fine becomes a
     * fixed amount. A loan still out is running up a figure that is not yet a
     * fine anybody owes, and including it would make the total move between two
     * readings of the same report.</p>
     */
    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.returnDate BETWEEN :from AND :to")
    double sumFinesRaisedAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    /** Fines settled within the range, counted on the day the payment was recorded. */
    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.finePaymentStatus = :paid AND t.finePaidAt BETWEEN :from AND :to")
    double sumFinesPaidAndLibraryId(@Param("paid") FinePaymentStatus paid,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("libraryId") Long libraryId);

    /**
     * Everything still owed, whenever it arose.
     *
     * <p>Not date-ranged, for the same reason the active loan count is not:
     * what is outstanding is a fact about now.</p>
     */
    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.finePaymentStatus = :unpaid")
    double sumOutstandingAndLibraryId(@Param("unpaid") FinePaymentStatus unpaid,
            @Param("libraryId") Long libraryId);

    /**
     * The books that went out most within the range.
     *
     * <p>Grouped and ordered by the database, and bounded by the {@link Pageable}
     * the caller supplies - so the answer is a handful of rows however long the
     * library's history is.</p>
     */
    @Query("SELECT b.title AS title, b.author AS author, COUNT(t) AS issues"
            + " FROM Transaction t JOIN t.book b"
            + " WHERE t.library.id = :libraryId AND t.issueDate BETWEEN :from AND :to"
            + " GROUP BY b.id, b.title, b.author ORDER BY COUNT(t) DESC, b.title ASC")
    List<TitleCount> mostIssuedAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId, Pageable pageable);

    /** The shelves borrowed from most within the range. Books with no category are left out. */
    @Query("SELECT c.name AS category, COUNT(t) AS issues"
            + " FROM Transaction t JOIN t.book b JOIN b.category c"
            + " WHERE t.library.id = :libraryId AND t.issueDate BETWEEN :from AND :to"
            + " GROUP BY c.id, c.name ORDER BY COUNT(t) DESC, c.name ASC")
    List<CategoryCount> popularCategoriesAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId, Pageable pageable);

    /** Issues per calendar month within the range, oldest first. */
    @Query("SELECT YEAR(t.issueDate) AS year, MONTH(t.issueDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.library.id = :libraryId AND t.issueDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(t.issueDate), MONTH(t.issueDate)"
            + " ORDER BY YEAR(t.issueDate) ASC, MONTH(t.issueDate) ASC")
    List<MonthCount> issuesByMonthAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    /** Returns per calendar month within the range, oldest first. */
    @Query("SELECT YEAR(t.returnDate) AS year, MONTH(t.returnDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.library.id = :libraryId AND t.returnDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(t.returnDate), MONTH(t.returnDate)"
            + " ORDER BY YEAR(t.returnDate) ASC, MONTH(t.returnDate) ASC")
    List<MonthCount> returnsByMonthAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    /** Loans that became overdue per month, counted on the day they were due. */
    @Query("SELECT YEAR(t.dueDate) AS year, MONTH(t.dueDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.library.id = :libraryId"
            + " AND t.dueDate BETWEEN :from AND :to AND t.fineAmount > 0"
            + " GROUP BY YEAR(t.dueDate), MONTH(t.dueDate)"
            + " ORDER BY YEAR(t.dueDate) ASC, MONTH(t.dueDate) ASC")
    List<MonthCount> overdueByMonthAndLibraryId(@Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("libraryId") Long libraryId);

    // ---------- every library ----------
    //
    // Each of these is the query above with its library condition removed, and
    // each says so in its name. They exist because a super administrator's
    // authority is the deployment's; nothing else reaches them.

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.issueDate BETWEEN :from AND :to")
    long countIssuesSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.returnDate BETWEEN :from AND :to")
    long countReturnsSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status IN :open")
    long countActiveLoansSystemWide(@Param("open") Collection<TransactionStatus> open);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status IN :open AND t.dueDate < :today")
    long countOverdueSystemWide(@Param("open") Collection<TransactionStatus> open,
            @Param("today") LocalDate today);

    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t"
            + " WHERE t.returnDate BETWEEN :from AND :to")
    double sumFinesRaisedSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t"
            + " WHERE t.finePaymentStatus = :paid AND t.finePaidAt BETWEEN :from AND :to")
    double sumFinesPaidSystemWide(@Param("paid") FinePaymentStatus paid,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(t.fineAmount), 0) FROM Transaction t WHERE t.finePaymentStatus = :unpaid")
    double sumOutstandingSystemWide(@Param("unpaid") FinePaymentStatus unpaid);

    @Query("SELECT b.title AS title, b.author AS author, COUNT(t) AS issues"
            + " FROM Transaction t JOIN t.book b WHERE t.issueDate BETWEEN :from AND :to"
            + " GROUP BY b.id, b.title, b.author ORDER BY COUNT(t) DESC, b.title ASC")
    List<TitleCount> mostIssuedSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to,
            Pageable pageable);

    @Query("SELECT c.name AS category, COUNT(t) AS issues"
            + " FROM Transaction t JOIN t.book b JOIN b.category c"
            + " WHERE t.issueDate BETWEEN :from AND :to"
            + " GROUP BY c.id, c.name ORDER BY COUNT(t) DESC, c.name ASC")
    List<CategoryCount> popularCategoriesSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to,
            Pageable pageable);

    @Query("SELECT YEAR(t.issueDate) AS year, MONTH(t.issueDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.issueDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(t.issueDate), MONTH(t.issueDate)"
            + " ORDER BY YEAR(t.issueDate) ASC, MONTH(t.issueDate) ASC")
    List<MonthCount> issuesByMonthSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT YEAR(t.returnDate) AS year, MONTH(t.returnDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.returnDate BETWEEN :from AND :to"
            + " GROUP BY YEAR(t.returnDate), MONTH(t.returnDate)"
            + " ORDER BY YEAR(t.returnDate) ASC, MONTH(t.returnDate) ASC")
    List<MonthCount> returnsByMonthSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT YEAR(t.dueDate) AS year, MONTH(t.dueDate) AS month, COUNT(t) AS total"
            + " FROM Transaction t WHERE t.dueDate BETWEEN :from AND :to AND t.fineAmount > 0"
            + " GROUP BY YEAR(t.dueDate), MONTH(t.dueDate)"
            + " ORDER BY YEAR(t.dueDate) ASC, MONTH(t.dueDate) ASC")
    List<MonthCount> overdueByMonthSystemWide(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
