package com.library.lms.exception;

/**
 * Thrown when a member already has a live request for the same book.
 *
 * <p>A conflict with the current state rather than a bad request: the same call
 * succeeds once the earlier request is decided or withdrawn. Only
 * {@code REQUESTED} and {@code APPROVED} count, so a member whose request was
 * rejected or already issued may ask again.</p>
 */
public class DuplicateBorrowRequestException extends RuntimeException {

    public DuplicateBorrowRequestException(String title) {
        super("You already have an open request for: " + title);
    }
}
