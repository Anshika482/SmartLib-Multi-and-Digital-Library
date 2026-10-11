package com.smartlib.circulation;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BorrowingRepository extends JpaRepository<Borrowing, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Borrowing b where b.id = :id and b.libraryId = :libraryId")
    Optional<Borrowing> lockByIdAndLibraryId(Long id, Long libraryId);

    Optional<Borrowing> findByIdAndLibraryId(Long id, Long libraryId);

    long countByMemberIdAndLibraryIdAndStatus(Long memberId, Long libraryId, BorrowingStatus status);

    boolean existsByMemberIdAndHoldingIdAndStatus(Long memberId, Long holdingId, BorrowingStatus status);
}
