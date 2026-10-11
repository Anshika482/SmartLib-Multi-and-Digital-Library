package com.smartlib.circulation;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartlib.catalogue.CatalogueDtos.BookSummary;

public final class CirculationDtos {
    private CirculationDtos() {
    }

    public record LibraryRef(long id, String name, String slug) {
    }

    public record MemberRef(long id, String fullName, String email, String phone) {
    }

    public record CreateRequest(@NotNull(message = "Choose a book") Long bookId,
                                @NotNull(message = "Choose the library you want to borrow from") Long libraryId,
                                @Size(max = 500) String note) {
    }

    public record RequestView(long id, RequestStatus status, BookSummary book, LibraryRef library, Instant createdAt,
                              Instant decidedAt, String decisionReason, LocalDate pickupDeadline, String assignedCopy,
                              String memberNote, Long borrowingId, MemberRef member, Integer availableCopies) {
    }

    public record LoanView(long id, BorrowingStatus status, BookSummary book, LibraryRef library, Instant issuedAt,
                           LocalDate dueDate, Instant returnedAt, ReturnCondition returnCondition, boolean overdue,
                           long overdueDays, boolean dueSoon, long estimatedFinePaise, String accessionNo, MemberRef member) {
    }

    public record ApproveRequest(Long copyId) {
    }

    public record RejectRequest(@NotBlank(message = "Please give a reason") @Size(max = 500) String reason) {
    }

    public record ReturnRequest(@NotNull ReturnCondition condition, @Size(max = 500) String note,
                                @Min(0) @Max(10_000_000) Long damageFeePaise, @Min(0) @Max(10_000_000) Long lostFeePaise) {
    }

    public record JoinLibraryRequest(@NotNull Long libraryId) {
    }

    public record MembershipView(long libraryId, String libraryName, String librarySlug, String status, Instant joinedAt) {
    }
}
