-- ==========================================================================
--  V12 - notifications
-- ==========================================================================
--  One row per notification this deployment has already sent.
--
--  The table exists for the unique key, and for very little else. Without it,
--  "tell the member their book was issued" would fire again every time an issue
--  was retried, a payment callback replayed, or the reminder sweep ran twice in
--  a day. With it, the second attempt cannot be inserted, and the send is
--  skipped rather than duplicated.
--
--  What it deliberately does not store:
--
--   * No email address. That belongs to the account and is read when the
--     message is built - a copy here would be a second place to update when
--     somebody changes it, and a second place for it to leak.
--   * No subject line and no body. A table of message bodies is a table of
--     everything the library has ever told anybody, kept for ever, for no
--     operational reason. The kind and the subject id are enough to know what
--     was sent.
--   * No foreign key on subject_id. It points at a loan, a request or an
--     account depending on the kind, and a notification should outlive the row
--     it describes rather than be deleted with it.
--
--  Additive throughout: one new table, and nothing existing is read, written,
--  renamed or dropped. Unlike V9 and V11 this widens no ENUM, so Hibernate's
--  ddl-auto=update creates it on a development database by itself and no
--  statement here needs applying by hand.
-- ==========================================================================

CREATE TABLE notification_log
(
    id                BIGINT      NOT NULL AUTO_INCREMENT,

    library_id        BIGINT      NOT NULL,

    -- An id rather than a foreign key to users, as audit_events records its
    -- actor: the notification is a fact about a moment, and it must survive
    -- that account being removed later.
    recipient_user_id BIGINT      NOT NULL,

    kind              ENUM('REGISTRATION_APPROVED', 'REGISTRATION_REJECTED',
                           'STAFF_APPLICATION_APPROVED', 'STAFF_APPLICATION_REJECTED',
                           'REQUEST_APPROVED', 'REQUEST_REJECTED',
                           'BOOK_ISSUED', 'BOOK_RETURNED',
                           'DUE_SOON', 'OVERDUE', 'FINE_RECEIPT') NOT NULL,

    -- The loan, request or account this is about.
    subject_id        BIGINT      NOT NULL,

    created_at        DATETIME(6) NOT NULL,

    outcome           ENUM('SENT', 'FAILED', 'NOT_CONFIGURED') NOT NULL,

    PRIMARY KEY (id),

    -- The rule, enforced by the database rather than by a check somebody could
    -- forget to write. One notification of one kind about one thing to one
    -- person, ever.
    UNIQUE KEY uk_notification_log_once (kind, subject_id, recipient_user_id),

    -- A library's own notifications, newest first, and one person's own.
    KEY idx_notification_log_library_sent (library_id, created_at),
    KEY idx_notification_log_recipient (recipient_user_id, created_at),

    CONSTRAINT fk_notification_log_library FOREIGN KEY (library_id) REFERENCES libraries (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
