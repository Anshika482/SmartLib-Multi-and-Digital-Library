package com.library.lms.exception;

/**
 * The upload is larger than a cover is allowed to be.
 *
 * <p>Carries the limit, so a person knows what would fit.
 */
public class CoverImageTooLargeException extends RuntimeException {

    public CoverImageTooLargeException(long maximumBytes) {
        super("A cover must be " + (maximumBytes / 1024) + " KB or smaller.");
    }
}
