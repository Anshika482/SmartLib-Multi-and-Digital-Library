-- Self-registration: members join, librarians and administrators apply.
--
-- Three changes, and two of them are ENUM widenings. MySQL will not store a
-- value its ENUM does not list, and Hibernate's ddl-auto=update does not widen
-- one - it adds columns and tables and nothing else. A database built before
-- this migration therefore needs it applied before any row can carry the new
-- values; see the note in the README about ENUM widening in development.
--
-- Existing values keep their exact spelling and their existing order, so the
-- ordinal of every value already stored is unchanged. The new values are
-- appended.


-- The system-level role. It decides applications that would open a new
-- library, which is the one decision no single library's administrator should
-- make. It is never produced by a registration: no registration type maps to
-- it, so no request can ask for it.
ALTER TABLE users
    MODIFY COLUMN role ENUM('ROLE_ADMIN', 'ROLE_LIBRARIAN', 'ROLE_MEMBER', 'ROLE_SUPER_ADMIN') NOT NULL;


-- What registration did, and who agreed to it.
--
-- Deliberately VARCHAR rather than ENUM: a later status would then need no
-- migration at all, and this column has no ordering worth preserving. NOT NULL
-- with a default of APPROVED, so every row that already exists - and every
-- account an administrator creates directly - is approved without this
-- migration having to write to it.
--
-- This does not gate authentication. `enabled` does, exactly as before; a
-- pending or rejected account is stored with enabled = FALSE, so it is refused
-- by the mechanism that already refuses a disabled account.
ALTER TABLE users
    ADD COLUMN registration_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED' AFTER account_non_locked;


-- The four things registration can record. USER_REGISTERED covers all three
-- kinds; LIBRARY_APPLIED is written alongside it when the registration would
-- open a library, so the application for the library is legible on its own.
ALTER TABLE audit_events
    MODIFY COLUMN action ENUM('USER_CREATED', 'USER_STATUS_CHANGED', 'PASSWORD_CHANGED', 'PASSWORD_RESET_BY_STAFF',
                              'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED', 'LIBRARY_CREATED',
                              'LIBRARY_BOOTSTRAPPED', 'BOOK_ISSUED', 'BOOK_RETURNED', 'FINE_PAID',
                              'USER_REGISTERED', 'REGISTRATION_APPROVED', 'REGISTRATION_REJECTED',
                              'LIBRARY_APPLIED') NOT NULL;
