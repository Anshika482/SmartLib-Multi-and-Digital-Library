package com.smartlib.library;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "library_policies")
@Getter
@Setter
public class LibraryPolicy {

    @Id
    private Long libraryId;

    private int loanPeriodDays = 14;
    private int maxActiveLoans = 5;
    private int maxPendingRequests = 3;
    private int pickupWindowDays = 3;
    private long finePerDayPaise = 500;
    private int graceDays = 0;
    private long maxFinePerLoanPaise = 50_000;
    private long blockThresholdPaise = 20_000;
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public static LibraryPolicy defaultsFor(long libraryId) {
        LibraryPolicy p = new LibraryPolicy();
        p.setLibraryId(libraryId);
        return p;
    }
}
