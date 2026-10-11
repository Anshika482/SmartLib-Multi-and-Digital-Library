package com.smartlib.catalogue;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LibraryHoldingRepository extends JpaRepository<LibraryHolding, Long> {

    Optional<LibraryHolding> findByIdAndLibraryId(Long id, Long libraryId);

    Optional<LibraryHolding> findByLibraryIdAndBookId(Long libraryId, Long bookId);

    @Query("select count(distinct h.libraryId) from LibraryHolding h where h.bookId = :bookId")
    long countLibrariesHoldingBook(Long bookId);

    @Query("select count(h) from LibraryHolding h where h.bookId = :bookId and h.libraryId <> :libraryId")
    long countOtherLibrariesHoldingBook(Long bookId, Long libraryId);
}
