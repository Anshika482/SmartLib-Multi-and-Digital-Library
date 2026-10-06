package com.library.lms.entity;

/**
 * The events a library tells somebody about.
 *
 * <p>The order below is the order the column declares, and new values are only
 * ever appended - MySQL stores an ENUM by position, so reordering these would
 * silently change what every stored row means.
 *
 * <p><b>Each value is one notification per subject per recipient, for ever.</b>
 * That is what makes the whole system idempotent: the pair of this and a subject
 * id is unique in {@code notification_log}, so retrying an issue, a return or a
 * reminder sweep cannot produce a second message. See
 * {@code NotificationService}.
 */
public enum NotificationKind {

    /** A member's own registration was approved. */
    REGISTRATION_APPROVED,

    /** A member's own registration was refused. */
    REGISTRATION_REJECTED,

    /** An application to work at a library was approved. */
    STAFF_APPLICATION_APPROVED,

    /** An application to work at a library was refused. */
    STAFF_APPLICATION_REJECTED,

    /** Staff agreed to a request for a book. It is not issued by this alone. */
    REQUEST_APPROVED,

    /** Staff refused a request for a book. */
    REQUEST_REJECTED,

    /** A copy was handed over. */
    BOOK_ISSUED,

    /** A copy came back. */
    BOOK_RETURNED,

    /** A loan is due in a few days. Sent once per loan. */
    DUE_SOON,

    /** A loan is past its due date. Sent once per loan. */
    OVERDUE,

    /** A fine was settled and the payment verified. */
    FINE_RECEIPT
}
