package com.library.lms.dto;

import java.time.LocalDateTime;

import com.library.lms.entity.BorrowRequestStatus;

/**
 * What the API says about one request for a book.
 *
 * <p>The entity stops at the service layer, as everywhere else in this
 * application: a {@code BorrowRequest} holds a {@code User}, and a User holds a
 * password hash, so returning the entity would put credential material one
 * Jackson call away from a response.
 *
 * <p><b>What is deliberately not here.</b> No library id - every caller only
 * ever sees their own, so the field would carry no information and invite a
 * client to send one back. No member id either: staff act on a request by its
 * own id, and the server reads the borrower from the row, so an id in the
 * response would only be an id a client could try substituting.
 *
 * @param id           this request
 * @param bookId       the book, so a screen can link to it
 * @param bookTitle    what a person reads
 * @param bookAuthor   shown beside the title, as in the catalogue
 * @param memberName   who asked; null on a member's own list, where it is them
 * @param status       where it has got to
 * @param requestedAt  when it was made
 * @param decidedAt    when it was approved, refused or withdrawn; null while waiting
 * @param transactionId the loan it became, or null
 */
public record BorrowRequestResponse(
        Long id,
        Long bookId,
        String bookTitle,
        String bookAuthor,
        String memberName,
        BorrowRequestStatus status,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        Long transactionId) {
}
