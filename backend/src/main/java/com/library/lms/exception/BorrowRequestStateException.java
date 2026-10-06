package com.library.lms.exception;

import com.library.lms.entity.BorrowRequestStatus;

/**
 * Thrown when a request is not in a state the attempted move allows.
 *
 * <p>Approving something already decided, cancelling a request that has been
 * issued, issuing one nobody approved: all invalid transitions, and all this.
 * The message names the state the request is actually in, which is what the
 * caller needs in order to know their screen is stale.</p>
 *
 * <p>A conflict, not a bad request. The call was well formed; the world moved.</p>
 */
public class BorrowRequestStateException extends RuntimeException {

    public BorrowRequestStateException(String attempted, BorrowRequestStatus actual) {
        super("Cannot " + attempted + " a request that is " + actual + ".");
    }
}
