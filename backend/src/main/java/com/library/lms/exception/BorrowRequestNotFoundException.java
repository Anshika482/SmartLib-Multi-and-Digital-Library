package com.library.lms.exception;

/**
 * Thrown when no request with this id exists in the caller's library.
 *
 * <p>One type for two situations, deliberately: a request that was never
 * created and a request belonging to another library are the same answer.
 * Splitting them would turn the id into a way to count a neighbour's queue.</p>
 */
public class BorrowRequestNotFoundException extends RuntimeException {

    public BorrowRequestNotFoundException(Long id) {
        super("Book request not found with id: " + id);
    }
}
