package com.library.lms.exception;

/**
 * Thrown when the caller may not act on this request at all.
 *
 * <p>Used for the ownership rule: a member may cancel their own request and
 * nobody else's. The request exists and is in a cancellable state - it simply
 * is not theirs.</p>
 */
public class BorrowRequestNotAllowedException extends RuntimeException {

    public BorrowRequestNotAllowedException(String message) {
        super(message);
    }
}
