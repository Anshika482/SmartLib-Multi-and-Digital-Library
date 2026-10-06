package com.library.lms.exception;

/**
 * Thrown when the caller may not read reports at all.
 *
 * <p>A report is about everybody's borrowing; a member is entitled to their
 * own, which their loans and fines screens show them. Also thrown for a member
 * of staff with no library, who has nothing to report on - answering them with
 * the whole deployment's figures would be the wrong direction to fail in.</p>
 */
public class ReportAccessDeniedException extends RuntimeException {

    public ReportAccessDeniedException() {
        super("Reports are available to library staff.");
    }
}
