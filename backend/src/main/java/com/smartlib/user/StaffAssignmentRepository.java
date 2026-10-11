package com.smartlib.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, Long> {

    Optional<StaffAssignment> findByUserId(Long userId);

    Optional<StaffAssignment> findByIdAndLibraryId(Long id, Long libraryId);

    List<StaffAssignment> findByLibraryIdAndApprovalStatus(Long libraryId, ApprovalStatus status);

    long countByLibraryIdAndApprovalStatus(Long libraryId, ApprovalStatus status);
}
