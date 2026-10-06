package com.library.lms.exception;

/**
 * A decision was made about a registration that is not waiting for one.
 *
 * <p>Already approved, already refused, or never an application at all. The
 * message is the same for all three: telling an approver which of those it was
 * would describe an account they may have no business seeing.</p>
 */
public class RegistrationNotPendingException extends RuntimeException {

    private static final String MESSAGE = "That registration is not awaiting a decision.";

    public RegistrationNotPendingException() {
        super(MESSAGE);
    }
}
