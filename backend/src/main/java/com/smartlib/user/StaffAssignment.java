package com.smartlib.user;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.Setter;

/**
 * Approval workflow for staff and platform accounts: links a LIBRARIAN/ADMIN account to exactly one library, or records
 * a SUPER_ADMIN application (no library). Accounts without a row (seeded, bootstrapped, invited SUPER_ADMINs) need no approval.
 */
@Entity
@Table(name = "staff_assignments")
@Getter
@Setter
public class StaffAssignment {

    /** {@link #origin} of rows created by public self-registration (invited and seeded rows have none). */
    public static final String ORIGIN_APPLICATION = "APPLICATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long libraryId;

    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    private String applicationNote;
    private String employeeRef;
    private String designation;
    private String origin;
    private Long reviewedBy;
    private Instant reviewedAt;
    private String rejectionReason;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
