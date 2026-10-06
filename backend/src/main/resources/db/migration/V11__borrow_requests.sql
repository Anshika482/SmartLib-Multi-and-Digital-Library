-- ==========================================================================
--  V11 - borrow requests
-- ==========================================================================
--  The step before a loan: a member asks for a book, staff agree or refuse,
--  and only then is a copy handed over by the existing issue path.
--
--  A request reserves nothing. There is no decrement here and no trigger: a
--  copy leaves the shelf when it is issued and not a moment earlier, so
--  availability stays a fact about the shelf rather than a forecast. That is
--  also why this table holds no due date, no fine and no return date - the
--  transactions table already owns all of that, and a request points at the
--  loan it became rather than restating it.
--
--  Three things are deliberately absent:
--
--   * No ON DELETE CASCADE. A request references a book, a member and a loan;
--     it does not own any of them, and a cascade here would let deleting a
--     request take a loan with it.
--   * No unique key over (user_id, book_id). The rule is one ACTIVE request
--     per member per book, and MySQL has no partial index to express "unique
--     only while status is REQUESTED or APPROVED". The rule lives in
--     BorrowRequestService, inside the transaction that writes the row.
--     transaction_id is unique, but that is a different rule and is enforced
--     below, because a request produces at most one loan.
--   * No status defaults. Every row is written by the service with a status
--     it chose; a default would quietly create rows nothing decided on.
--
--  Additive throughout. Nothing existing is read, written, renamed or dropped.
-- ==========================================================================

CREATE TABLE borrow_requests
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,

    -- Optimistic locking, as on books and transactions. Two members of staff
    -- approving the same pending request would otherwise both succeed.
    version            BIGINT      NULL,

    book_id            BIGINT      NOT NULL,
    user_id            BIGINT      NOT NULL,

    -- Stored rather than reached through the book, because every query in this
    -- feature is scoped by it and a scoped query cannot depend on a join to
    -- the row it is scoping.
    library_id         BIGINT      NOT NULL,

    status             ENUM('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'FULFILLED') NOT NULL,

    requested_at       DATETIME(6) NOT NULL,

    -- Null while the request is still waiting.
    decided_at         DATETIME(6) NULL,

    -- An id rather than a foreign key to users, exactly as audit_events records
    -- its actor: the decision is a fact about a person at a moment, and it must
    -- survive that account being removed later. Null for a cancellation, which
    -- the member made themselves.
    decided_by_user_id BIGINT      NULL,

    -- The loan this became, once a copy was handed over. Null until then, and
    -- null forever on a request that was refused or withdrawn.
    transaction_id     BIGINT      NULL,

    PRIMARY KEY (id),

    -- A request produces at most one loan, which the @OneToOne mapping on
    -- BorrowRequest.transaction states and this enforces. Without it the
    -- database would let two requests claim the same loan, and the mapping
    -- would be a promise only the Java side kept.
    UNIQUE KEY uk_borrow_requests_transaction (transaction_id),

    -- The two listings this feature serves: a member's own requests, newest
    -- first, and a library's queue filtered by status.
    KEY idx_borrow_requests_user (user_id, requested_at),
    KEY idx_borrow_requests_library_status (library_id, status, requested_at),

    -- Used by the duplicate check, which asks whether this member already has
    -- an active request for this book.
    KEY idx_borrow_requests_user_book_status (user_id, book_id, status),

    CONSTRAINT fk_borrow_requests_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_borrow_requests_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_borrow_requests_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_borrow_requests_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ==========================================================================
--  Audit: four new actions and one new target kind.
--
--  Additive only, and in the order the Java enums declare. Both statements
--  repeat every value the column already holds, in its existing position -
--  MySQL stores an ENUM as the value's position, so leaving the existing ones
--  where they are keeps every stored row meaning exactly what it meant before.
--
--  NOTE for an existing development database: ddl-auto=update does not widen
--  an ENUM that already exists, and this project runs Flyway only under the
--  prod profile. A local library_db created before this migration needs these
--  two statements applied by hand, or its audit_events table recreated, before
--  a request can be approved. See the README.
-- ==========================================================================

ALTER TABLE audit_events
    MODIFY COLUMN action ENUM('USER_CREATED', 'USER_STATUS_CHANGED', 'PASSWORD_CHANGED', 'PASSWORD_RESET_BY_STAFF',
                              'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED', 'LIBRARY_CREATED',
                              'LIBRARY_BOOTSTRAPPED', 'BOOK_ISSUED', 'BOOK_RETURNED', 'FINE_PAID',
                              'USER_REGISTERED', 'REGISTRATION_APPROVED', 'REGISTRATION_REJECTED',
                              'LIBRARY_APPLIED', 'REQUEST_CREATED', 'REQUEST_APPROVED', 'REQUEST_REJECTED',
                              'REQUEST_CANCELLED') NOT NULL;

ALTER TABLE audit_events
    MODIFY COLUMN target_type ENUM('USER', 'LIBRARY', 'LOAN', 'REQUEST') NULL;
