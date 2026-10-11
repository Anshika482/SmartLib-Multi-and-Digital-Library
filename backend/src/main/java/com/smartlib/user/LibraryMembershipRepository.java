package com.smartlib.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryMembershipRepository extends JpaRepository<LibraryMembership, Long> {

    Optional<LibraryMembership> findByUserIdAndLibraryId(Long userId, Long libraryId);

    List<LibraryMembership> findByUserId(Long userId);

    List<LibraryMembership> findByLibraryIdAndStatus(Long libraryId, MembershipStatus status);

    long countByLibraryIdAndStatus(Long libraryId, MembershipStatus status);
}
