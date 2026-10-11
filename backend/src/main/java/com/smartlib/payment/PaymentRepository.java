package com.smartlib.payment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerOrderId = :orderId")
    Optional<Payment> lockByOrderId(String orderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    Optional<Payment> findFirstByFineIdAndStatusAndCreatedAtAfterOrderByIdDesc(Long fineId, PaymentStatus status, Instant after);

    @Query("select p.id from Payment p where p.status = com.smartlib.payment.PaymentStatus.CREATED and p.createdAt < :before")
    List<Long> findStale(Instant before);
}
