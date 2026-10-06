package com.library.lms.exception;

/**
 * Thrown when a report's dates cannot be counted over.
 *
 * <p>An inverted range would answer zero for every figure, which reads as "your
 * library did nothing" rather than "you asked backwards" - so it is refused
 * with a message saying which. A range is also the only thing a caller controls
 * about how much work the database does, so there is a ceiling on it.</p>
 */
public class InvalidReportRangeException extends RuntimeException {

    public InvalidReportRangeException(String message) {
        super(message);
    }
}
