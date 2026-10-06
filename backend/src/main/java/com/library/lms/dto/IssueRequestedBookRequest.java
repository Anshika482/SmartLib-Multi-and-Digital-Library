package com.library.lms.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Handing over the copy an approved request was waiting for.
 *
 * <p>Only the due date. The book and the borrower are read from the request
 * being fulfilled, not from this payload - naming them here would let a caller
 * fulfil one member's request with another member's loan.</p>
 *
 * <p>The two annotations are not redundant: {@code @FutureOrPresent} passes a
 * null value, so without {@code @NotNull} a missing date would reach the
 * service. The service checks both again, because a caller can reach it
 * without passing through this class.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class IssueRequestedBookRequest {

    @NotNull(message = "Due date is required")
    @FutureOrPresent(message = "Due date must be today or a future date")
    private LocalDate dueDate;
}
