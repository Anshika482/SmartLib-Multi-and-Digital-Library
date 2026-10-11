package com.smartlib.catalogue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookCopyRepository extends JpaRepository<BookCopy, Long> {

    Optional<BookCopy> findByIdAndLibraryId(Long id, Long libraryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BookCopy c where c.id = :id and c.libraryId = :libraryId")
    Optional<BookCopy> lockByIdAndLibraryId(Long id, Long libraryId);

    /** Picks and locks one available copy; concurrent approvals skip each other's locked rows. */
    @Query(value = "SELECT * FROM book_copies WHERE holding_id = :holdingId AND status = 'AVAILABLE' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<BookCopy> lockFirstAvailable(Long holdingId);

    List<BookCopy> findByHoldingIdOrderByIdAsc(Long holdingId);

    long countByHoldingIdAndStatus(Long holdingId, CopyStatus status);

    long countByHoldingIdAndStatusIn(Long holdingId, Collection<CopyStatus> statuses);

    long countByHoldingId(Long holdingId);
}
