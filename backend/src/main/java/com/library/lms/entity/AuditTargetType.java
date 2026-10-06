package com.library.lms.entity;

/** The kind of thing an audit event acted on. Together with the target id it names one record. */
public enum AuditTargetType {

    USER,

    LIBRARY,

    /** A loan: one transaction row, by its id. */
    LOAN,

    /** A member's request for a book, by its id. Appended, like every value before it. */
    REQUEST
}
