package com.library.lms.entity;

/**
 * What an audit event records: the security-sensitive changes to accounts,
 * passwords and libraries.
 *
 * <p>Stored by name in a MySQL {@code ENUM}, so adding an action is a
 * migration - deliberate friction for a list that should only grow with
 * thought. Reads are not audited; only changes are.</p>
 */
public enum AuditAction {

    /** An administrator created a member or librarian account. */
    USER_CREATED,

    /** An administrator enabled, disabled, locked or unlocked an account. */
    USER_STATUS_CHANGED,

    /** An account holder changed their own password, signed in. */
    PASSWORD_CHANGED,

    /** A member of staff set a new password for someone who had forgotten theirs. */
    PASSWORD_RESET_BY_STAFF,

    /** A self-service reset token was issued - or refused - for an account. */
    PASSWORD_RESET_REQUESTED,

    /** A self-service reset token was redeemed - or refused - for an account. */
    PASSWORD_RESET_COMPLETED,

    /** An administrator registered a new library with its first administrator. */
    LIBRARY_CREATED,

    /** The first library and administrator were created at startup from configuration. */
    LIBRARY_BOOTSTRAPPED,

    /** Staff issued a book to a member - or were refused before a loan existed. */
    BOOK_ISSUED,

    /** Staff took a book back and closed the loan - or were refused. */
    BOOK_RETURNED,

    /** Staff recorded that a loan's fine was paid - or were refused. */
    FINE_PAID,

    /** Somebody registered themselves - as a member, or as an application awaiting approval. */
    USER_REGISTERED,

    /** A pending registration was approved, and the account became usable. */
    REGISTRATION_APPROVED,

    /** A pending registration was refused. The account stays disabled. */
    REGISTRATION_REJECTED,

    /** A new library was applied for, together with the account that would administer it. */
    LIBRARY_APPLIED,

    /**
     * A member asked for a book.
     *
     * <p>Appended, like every value before them: MySQL stores an ENUM by
     * position, so these four go at the end and nothing already written changes
     * meaning.</p>
     */
    REQUEST_CREATED,

    /** Staff agreed to a request. The book is not issued by this alone. */
    REQUEST_APPROVED,

    /** Staff refused a request. */
    REQUEST_REJECTED,

    /** A member withdrew their own request. */
    REQUEST_CANCELLED
}
