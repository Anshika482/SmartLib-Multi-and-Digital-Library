package com.library.lms.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A member asking for a book.
 *
 * <p>One field, and the absence of the second is the point: there is no
 * {@code memberId} here. The borrower is the authenticated caller, resolved
 * from the token by the service, so this payload offers no way to queue for a
 * book on somebody else's behalf. There is no {@code libraryId} either - the
 * library is the caller's own and is never something a client states.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CreateBorrowRequest {

    @NotNull(message = "Book id is required")
    @Positive(message = "Book id must be a positive number")
    private Long bookId;
}
