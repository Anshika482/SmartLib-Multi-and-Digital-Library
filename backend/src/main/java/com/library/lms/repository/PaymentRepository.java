package com.library.lms.repository;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Payment;
import com.library.lms.entity.PaymentStatus;

/**
 * Data access for {@link Payment}.
 *
 * <p>Every finder names the library, so a payment of one library is never
 * reachable from another - the same rule the loan, book and account
 * repositories follow.</p>
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** One order of a library, by the provider's reference. */
    Optional<Payment> findByLibraryIdAndProviderOrderId(Long libraryId, String providerOrderId);

    /** Whether this loan already has a payment in a given state - how a second success is refused. */
    boolean existsByTransactionIdAndStatus(Long transactionId, PaymentStatus status);

    /** An open order for a loan, so asking twice reopens rather than piles up. */
    Optional<Payment> findFirstByTransactionIdAndStatusOrderByIdDesc(Long transactionId, PaymentStatus status);

    /**
     * What one library actually took through the gateway within a range.
     *
     * <p>Only SUCCEEDED payments: an order that was opened and abandoned is not
     * money, and counting it would overstate takings by however many people
     * changed their mind.</p>
     *
     * <p>Counted on {@code completedAt}, the moment the signature verified,
     * rather than on when the order was opened - those can be different days.</p>
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.library.id = :libraryId"
            + " AND p.status = :status AND p.completedAt BETWEEN :from AND :to")
    double sumTakenAndLibraryId(@Param("status") PaymentStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            @Param("libraryId") Long libraryId);

    /**
     * The same, across every library.
     *
     * <p>Named for what it does, and reached only from the super administrator
     * branch of {@code ReportService} - the same arrangement
     * {@code ReportRepository} uses.</p>
     */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p"
            + " WHERE p.status = :status AND p.completedAt BETWEEN :from AND :to")
    double sumTakenSystemWide(@Param("status") PaymentStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
