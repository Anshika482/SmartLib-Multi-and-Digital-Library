package com.library.lms.exception;

/**
 * Something went wrong with the store itself - a full disk, a permission,
 * a broken path. The message says nothing about where the store is or what
 * went wrong with it: that belongs in the log, which the operator can read
 * and a caller cannot.
 */
public class CoverImageStorageException extends RuntimeException {

    private static final String MESSAGE = "A cover image could not be stored, read or removed.";

    public CoverImageStorageException() {
        super(MESSAGE);
    }
}
