package com.smartlib.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(UserRole role);

    /** True when a SUPER_ADMIN exists that can sign in (no application, or an approved one). */
    @Query("""
            select count(u) > 0 from User u where u.role = com.smartlib.user.UserRole.SUPER_ADMIN
              and u.status = com.smartlib.user.UserStatus.ACTIVE
              and not exists (select 1 from StaffAssignment s where s.userId = u.id
                              and s.approvalStatus <> com.smartlib.user.ApprovalStatus.APPROVED)""")
    boolean existsUsableSuperAdmin();

    long countByRole(UserRole role);

    java.util.List<User> findByRole(UserRole role);
}
