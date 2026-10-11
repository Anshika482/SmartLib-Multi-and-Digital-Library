package com.smartlib.fine;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface FineRepository extends JpaRepository<Fine, Long> {

    @Query("select coalesce(sum(f.amountPaise), 0) from Fine f where f.memberId = :memberId and f.libraryId = :libraryId"
            + " and f.status = com.smartlib.fine.FineStatus.UNPAID")
    long sumUnpaid(Long memberId, Long libraryId);

    @Query("select coalesce(sum(f.amountPaise), 0) from Fine f where f.memberId = :memberId and f.status = com.smartlib.fine.FineStatus.UNPAID")
    long sumUnpaidAll(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id = :id and f.memberId = :memberId")
    Optional<Fine> lockByIdAndMemberId(Long id, Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id = :id and f.libraryId = :libraryId")
    Optional<Fine> lockByIdAndLibraryId(Long id, Long libraryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Fine f where f.id = :id")
    Optional<Fine> lockById(Long id);
}
