package com.smartlib.circulation;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BorrowRequestRepository extends JpaRepository<BorrowRequest, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BorrowRequest r where r.id = :id and r.libraryId = :libraryId")
    Optional<BorrowRequest> lockByIdAndLibraryId(Long id, Long libraryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BorrowRequest r where r.id = :id and r.memberId = :memberId")
    Optional<BorrowRequest> lockByIdAndMemberId(Long id, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BorrowRequest r where r.id = :id")
    Optional<BorrowRequest> lockById(Long id);

    boolean existsByMemberIdAndHoldingIdAndStatusIn(Long memberId, Long holdingId, Collection<RequestStatus> statuses);

    long countByMemberIdAndLibraryIdAndStatus(Long memberId, Long libraryId, RequestStatus status);

    @Query("select r.id from BorrowRequest r where r.status = com.smartlib.circulation.RequestStatus.APPROVED and r.pickupDeadline < :today")
    List<Long> findExpiredPickups(LocalDate today);
}
