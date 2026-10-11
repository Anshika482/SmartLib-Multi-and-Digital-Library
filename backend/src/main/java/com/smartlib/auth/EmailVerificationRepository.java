package com.smartlib.auth;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    Optional<EmailVerification> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findFirstByUserIdAndStatusInOrderByCreatedAtDesc(Long userId, Collection<VerificationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerification> findByLinkTokenHash(String linkTokenHash);

    long countByUserIdAndCreatedAtAfter(Long userId, Instant after);

    Optional<EmailVerification> findFirstByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, Instant after);

    @Modifying
    @Query("update EmailVerification e set e.status = :to where e.userId = :userId and e.status in :from")
    int updateStatus(Long userId, Collection<VerificationStatus> from, VerificationStatus to);
}
