package com.library.lms.exception;

/**
 * A library somebody tried to join, which they cannot.
 *
 * <p>One message for two situations on purpose: the library does not exist, and
 * the library exists but has no approved administrator yet. Telling those apart
 * would let a stranger probe for libraries that have been applied for and not
 * yet approved, which is nobody else business while it is being decided.
 *
 * <p>Registration is public, so this is the answer an unauthenticated caller
 * gets. It names no library and confirms nothing.
 */
public class LibraryNotJoinableException extends RuntimeException {

    private static final String MESSAGE = "That library is not available to join.";

    public LibraryNotJoinableException() {
        super(MESSAGE);
    }
}
