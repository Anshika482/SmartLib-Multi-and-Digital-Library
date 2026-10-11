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

@Entity
@Table(name = "borrowings")
@Getter
@Setter
public class Borrowing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long requestId;
    private Long memberId;
    private Long libraryId;
    private Long holdingId;
    private Long bookId;
    private Long copyId;
    private Long issuedBy;
    private Instant issuedAt;
    private LocalDate dueDate;
    private Instant returnedAt;
    private Long returnedTo;

    @Enumerated(EnumType.STRING)
    private ReturnCondition returnCondition;

    @Enumerated(EnumType.STRING)
    private BorrowingStatus status;

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
