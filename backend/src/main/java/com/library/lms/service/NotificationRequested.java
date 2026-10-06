package com.library.lms.service;

import com.library.lms.entity.NotificationKind;

/**
 * Something happened that somebody should be told about.
 *
 * <p>Published by the service that did the thing, and handled after that
 * service's transaction commits - so nobody is ever told about a loan that was
 * rolled back.
 *
 * <p><b>Ids only, and deliberately.</b> No address, no name, no book title and
 * no amount. The listener reads what it needs from the database when it builds
 * the message, which means three things: the recipient is resolved
 * <b>server-side</b> from an id rather than taken from whatever published the
 * event; the message reflects the account as it is at the moment of sending, so
 * a member disabled in between is not written to; and an event sitting in a
 * queue is not a copy of somebody's personal details.
 *
 * @param kind        what happened
 * @param libraryId   the library it happened in, which scopes the record of it
 * @param recipientId the account to tell, by id
 * @param subjectId   the loan, request or account it is about
 */
public record NotificationRequested(NotificationKind kind, Long libraryId, Long recipientId, Long subjectId) {

    public NotificationRequested {
        if (kind == null || libraryId == null || recipientId == null || subjectId == null) {
            throw new IllegalArgumentException("A notification needs a kind, a library, a recipient and a subject");
        }
    }
}
