package com.library.lms.exception;

/**
 * A cover was asked for that is not there.
 *
 * <p>The same answer whether the book never had one, or had one and the file
 * has since gone. Neither is a caller's problem to tell apart, and the second
 * says something about the store.
 */
public class CoverImageNotFoundException extends RuntimeException {

    private static final String MESSAGE = "This book has no cover image.";

    public CoverImageNotFoundException() {
        super(MESSAGE);
    }
}
