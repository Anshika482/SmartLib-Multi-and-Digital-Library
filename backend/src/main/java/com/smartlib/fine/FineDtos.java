package com.smartlib.fine;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.circulation.CirculationDtos.LibraryRef;
import com.smartlib.circulation.CirculationDtos.MemberRef;

public final class FineDtos {
    private FineDtos() {
    }

    public record FineView(long id, FineType type, FineStatus status, long amountPaise, Integer overdueDays, String reason,
                           Instant createdAt, Instant paidAt, LibraryRef library, BookSummary book, Long borrowingId,
                           LocalDate dueDate, Instant returnedAt, String waivedReason, Long paymentId, MemberRef member) {
    }

    public record FineSummary(long unpaidCount, long unpaidPaise, long paidPaise, long waivedPaise) {
    }

    public record CreateFineRequest(@NotNull Long memberId, Long borrowingId, @NotNull FineType type,
                                    @NotNull @Min(value = 100, message = "Minimum fine is ₹1") @Max(10_000_000) Long amountPaise,
                                    @NotBlank(message = "Please give a reason") @Size(max = 500) String reason) {
    }

    public record WaiveRequest(@NotBlank(message = "Please give a reason") @Size(max = 500) String reason) {
    }
}
