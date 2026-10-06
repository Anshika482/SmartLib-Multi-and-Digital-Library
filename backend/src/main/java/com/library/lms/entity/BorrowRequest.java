package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One member asking for one book.
 *
 * <p>A request is the step before a loan, not a kind of loan. It reserves
 * nothing: availability is not decremented when a request is made or approved,
 * because a copy leaves the shelf when it is handed over and not before. What
 * the row records is an intention and the decision taken on it.
 *
 * <p><b>The loan is still the source of truth about the book.</b> When an
 * approved request is issued, the existing issue path writes the
 * {@link Transaction} and moves the stock; this row only points at the loan
 * that resulted and goes terminal. Nothing here duplicates what a transaction
 * already knows, which is why there is no due date, no fine and no return date
 * on it.
 *
 * <p>Relationships are unidirectional for the same reason {@link Transaction}'s
 * are: a book or a member with an unbounded collection of requests is a
 * collection JPA wants to manage and nothing needs to walk. No cascade either -
 * a request references a book and a member, it does not own them.
 */
@Entity
@Table(
        name = "borrow_requests",
        // Named here as well as in V11, and that is the project's convention
        // rather than decoration: FlywayMigrationIntegrationTest builds one
        // schema from the migrations and another from these mappings and
        // compares them fact for fact, index names included. An unnamed
        // constraint gets a different generated name on each side and the two
        // schemas stop matching - see DigitalResource, which does the same.
        indexes = {
                @Index(name = "idx_borrow_requests_user", columnList = "user_id, requested_at"),
                @Index(name = "idx_borrow_requests_library_status",
                        columnList = "library_id, status, requested_at"),
                @Index(name = "idx_borrow_requests_user_book_status",
                        columnList = "user_id, book_id, status")
        },
        // A request produces at most one loan. Declared rather than left to the
        // @OneToOne below, so the constraint carries a name the migration can
        // use too - without it the database would allow two requests to claim
        // the same loan.
        uniqueConstraints = @UniqueConstraint(name = "uk_borrow_requests_transaction",
                columnNames = "transaction_id"))
@Getter
@Setter
@ToString
@NoArgsConstructor
public class BorrowRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Row version, maintained by Hibernate for optimistic locking.
     *
     * <p>Two members of staff opening the same pending request and both
     * approving it would otherwise each read REQUESTED, each pass the state
     * check and each write a decision. With a version column the second write
     * matches no row and fails, so one decision is recorded rather than two.
     *
     * <p>Never assigned by hand - Hibernate owns this field.</p>
     */
    @Version
    private Long version;

    /** The book wanted. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "book_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_borrow_requests_book"))
    private Book book;

    /** The member who wants it. Never a member of staff. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_borrow_requests_user"))
    private User user;

    /**
     * The library this request belongs to.
     *
     * <p>Stored explicitly rather than read through the book, for the same
     * reason {@link Transaction} stores it: every query in this feature is
     * scoped by it, and a scoped query cannot depend on a join to the row it is
     * scoping. Set from the requesting member's own account, so the book, the
     * member and this column agree by construction.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_borrow_requests_library"))
    private Library library;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BorrowRequestStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** When staff approved or rejected it, or the member withdrew it. Null while it waits. */
    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /**
     * Who decided.
     *
     * <p>An id rather than a relationship, exactly as {@code AuditEvent} records
     * its actor: the decision is a fact about a person at a moment, and loading
     * an account to read this row would drag a password hash along behind it.
     * Null while the request waits, and null for a cancellation, which the
     * member made themselves.
     */
    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    /**
     * The loan this request turned into, once a copy was handed over.
     *
     * <p>Null until then, and null forever on a request that was rejected or
     * withdrawn. One-to-one because a request produces at most one loan; the
     * loan does not point back, so nothing about issuing had to change to
     * accommodate this.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id",
            foreignKey = @ForeignKey(name = "fk_borrow_requests_transaction"))
    private Transaction transaction;
}
