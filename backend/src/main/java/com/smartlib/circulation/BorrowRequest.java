package com.smartlib.circulation;

import java.time.Instant;
import java.time.LocalDate;

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

/** A member's request for a specific title at a specific library (Book + Library + Member). */
@Entity
@Table(name = "borrow_requests")
@Getter
@Setter
public class BorrowRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long memberId;
    private Long libraryId;
    private Long holdingId;
    private Long bookId;

    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    private Long assignedCopyId;
    private String memberNote;
    private String decisionReason;
    private Long decidedBy;
    private Instant decidedAt;
    private LocalDate pickupDeadline;
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
