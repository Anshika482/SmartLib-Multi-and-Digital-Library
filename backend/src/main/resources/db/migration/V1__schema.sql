-- SmartLib schema v1
-- Conventions: BIGINT ids, enums stored as VARCHAR + CHECK, UTC DATETIME(6) timestamps, money in paise (BIGINT).

CREATE TABLE libraries (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL,
    slug            VARCHAR(80)  NOT NULL,
    name            VARCHAR(160) NOT NULL,
    tagline         VARCHAR(160),
    description     TEXT,
    address_line    VARCHAR(255),
    city            VARCHAR(100),
    state           VARCHAR(100),
    postal_code     VARCHAR(20),
    public_email    VARCHAR(254),
    public_phone    VARCHAR(32),
    website         VARCHAR(255),
    opening_hours   VARCHAR(255),
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Kolkata',
    image_key       VARCHAR(255),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_library_code UNIQUE (code),
    CONSTRAINT uk_library_slug UNIQUE (slug),
    CONSTRAINT ck_library_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE library_policies (
    library_id               BIGINT PRIMARY KEY,
    loan_period_days         INT    NOT NULL DEFAULT 14,
    max_active_loans         INT    NOT NULL DEFAULT 5,
    max_pending_requests     INT    NOT NULL DEFAULT 3,
    pickup_window_days       INT    NOT NULL DEFAULT 3,
    fine_per_day_paise       BIGINT NOT NULL DEFAULT 500,
    grace_days               INT    NOT NULL DEFAULT 0,
    max_fine_per_loan_paise  BIGINT NOT NULL DEFAULT 50000,
    block_threshold_paise    BIGINT NOT NULL DEFAULT 20000,
    updated_at               DATETIME(6) NOT NULL,
    CONSTRAINT fk_policy_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT ck_policy_values CHECK (loan_period_days > 0 AND max_active_loans > 0 AND max_pending_requests > 0
        AND pickup_window_days > 0 AND fine_per_day_paise >= 0 AND grace_days >= 0 AND max_fine_per_loan_paise >= 0
        AND block_threshold_paise >= 0)
);

CREATE TABLE users (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    email              VARCHAR(254) NOT NULL,
    password_hash      VARCHAR(100) NOT NULL,
    full_name          VARCHAR(120) NOT NULL,
    phone              VARCHAR(20),
    role               VARCHAR(20)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    email_verified_at  DATETIME(6),
    token_version      INT          NOT NULL DEFAULT 0,
    terms_accepted_at  DATETIME(6),
    last_login_at      DATETIME(6),
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('MEMBER', 'LIBRARIAN', 'ADMIN', 'SUPER_ADMIN')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED', 'INVITED'))
);
CREATE INDEX idx_users_role ON users (role, created_at);

CREATE TABLE staff_assignments (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    library_id        BIGINT       NOT NULL,
    approval_status   VARCHAR(16)  NOT NULL,
    application_note  VARCHAR(1000),
    employee_ref      VARCHAR(64),
    reviewed_by       BIGINT,
    reviewed_at       DATETIME(6),
    rejection_reason  VARCHAR(500),
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_staff_user UNIQUE (user_id),
    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_staff_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_staff_reviewer FOREIGN KEY (reviewed_by) REFERENCES users (id),
    CONSTRAINT ck_staff_status CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'))
);
CREATE INDEX idx_staff_library_status ON staff_assignments (library_id, approval_status);

CREATE TABLE library_memberships (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    library_id  BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL,
    joined_at   DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_membership UNIQUE (user_id, library_id),
    CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_membership_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE INDEX idx_membership_library ON library_memberships (library_id, status);

CREATE TABLE categories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(80) NOT NULL,
    slug        VARCHAR(80) NOT NULL,
    sort_order  INT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_category_slug UNIQUE (slug),
    CONSTRAINT uk_category_name UNIQUE (name)
);

CREATE TABLE authors (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(160) NOT NULL,
    name_normalized  VARCHAR(160) NOT NULL,
    CONSTRAINT uk_author_name UNIQUE (name_normalized)
);

CREATE TABLE books (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    isbn13                 CHAR(13),
    isbn10                 CHAR(10),
    title                  VARCHAR(300) NOT NULL,
    subtitle               VARCHAR(300),
    description            TEXT,
    publisher              VARCHAR(200),
    published_year         INT,
    language               VARCHAR(40),
    page_count             INT,
    category_id            BIGINT,
    cover_source           VARCHAR(16)  NOT NULL DEFAULT 'UNCHECKED',
    cover_key              VARCHAR(255),
    cover_external_ref     VARCHAR(500),
    cover_checked_at       DATETIME(6),
    search_text            TEXT         NOT NULL,
    created_by_library_id  BIGINT,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_books_isbn13 UNIQUE (isbn13),
    CONSTRAINT fk_books_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_books_created_by FOREIGN KEY (created_by_library_id) REFERENCES libraries (id),
    CONSTRAINT ck_books_cover CHECK (cover_source IN ('UNCHECKED', 'LOCAL', 'OPEN_LIBRARY', 'GOOGLE_BOOKS', 'NONE'))
);
CREATE INDEX idx_books_category ON books (category_id);
CREATE INDEX idx_books_title ON books (title(100));
CREATE INDEX idx_books_cover ON books (cover_source, cover_checked_at);
CREATE INDEX idx_books_created ON books (created_at);
CREATE FULLTEXT INDEX ft_books_search ON books (search_text);

CREATE TABLE book_authors (
    book_id    BIGINT NOT NULL,
    author_id  BIGINT NOT NULL,
    position   INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (book_id, author_id),
    CONSTRAINT fk_ba_book FOREIGN KEY (book_id) REFERENCES books (id) ON DELETE CASCADE,
    CONSTRAINT fk_ba_author FOREIGN KEY (author_id) REFERENCES authors (id)
);
CREATE INDEX idx_ba_author ON book_authors (author_id);

CREATE TABLE library_holdings (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    library_id      BIGINT      NOT NULL,
    book_id         BIGINT      NOT NULL,
    shelf_location  VARCHAR(64),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_holding UNIQUE (library_id, book_id),
    CONSTRAINT fk_holding_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_holding_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT ck_holding_status CHECK (status IN ('ACTIVE', 'WITHDRAWN'))
);
CREATE INDEX idx_holding_book ON library_holdings (book_id, status);

CREATE TABLE book_copies (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    holding_id      BIGINT      NOT NULL,
    library_id      BIGINT      NOT NULL,
    accession_no    VARCHAR(48) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    condition_note  VARCHAR(500),
    acquired_at     DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_copy_accession UNIQUE (library_id, accession_no),
    CONSTRAINT fk_copy_holding FOREIGN KEY (holding_id) REFERENCES library_holdings (id),
    CONSTRAINT fk_copy_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT ck_copy_status CHECK (status IN ('AVAILABLE', 'RESERVED', 'BORROWED', 'LOST', 'DAMAGED', 'WITHDRAWN'))
);
CREATE INDEX idx_copy_holding_status ON book_copies (holding_id, status);
CREATE INDEX idx_copy_library_status ON book_copies (library_id, status);

CREATE TABLE borrow_requests (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id         BIGINT      NOT NULL,
    library_id        BIGINT      NOT NULL,
    holding_id        BIGINT      NOT NULL,
    book_id           BIGINT      NOT NULL,
    status            VARCHAR(16) NOT NULL,
    assigned_copy_id  BIGINT,
    member_note       VARCHAR(500),
    decision_reason   VARCHAR(500),
    decided_by        BIGINT,
    decided_at        DATETIME(6),
    pickup_deadline   DATE,
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    -- At most one PENDING/APPROVED request per member per library holding (enforced by the database).
    active_key        VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN status IN ('PENDING', 'APPROVED') THEN CONCAT(member_id, ':', holding_id) ELSE NULL END) STORED,
    CONSTRAINT uk_request_active UNIQUE (active_key),
    CONSTRAINT fk_req_member FOREIGN KEY (member_id) REFERENCES users (id),
    CONSTRAINT fk_req_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_req_holding FOREIGN KEY (holding_id) REFERENCES library_holdings (id),
    CONSTRAINT fk_req_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_req_copy FOREIGN KEY (assigned_copy_id) REFERENCES book_copies (id),
    CONSTRAINT fk_req_decider FOREIGN KEY (decided_by) REFERENCES users (id),
    CONSTRAINT ck_req_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'FULFILLED'))
);
CREATE INDEX idx_req_library_status ON borrow_requests (library_id, status, created_at);
CREATE INDEX idx_req_member_status ON borrow_requests (member_id, status, created_at);
CREATE INDEX idx_req_pickup ON borrow_requests (status, pickup_deadline);

CREATE TABLE borrowings (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         BIGINT,
    member_id          BIGINT      NOT NULL,
    library_id         BIGINT      NOT NULL,
    holding_id         BIGINT      NOT NULL,
    book_id            BIGINT      NOT NULL,
    copy_id            BIGINT      NOT NULL,
    issued_by          BIGINT      NOT NULL,
    issued_at          DATETIME(6) NOT NULL,
    due_date           DATE        NOT NULL,
    returned_at        DATETIME(6),
    returned_to        BIGINT,
    return_condition   VARCHAR(16),
    status             VARCHAR(16) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    -- A copy can be in at most one ACTIVE loan; a member can hold a title from one library at most once.
    active_copy_key    BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN copy_id ELSE NULL END) STORED,
    active_member_key  VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN status = 'ACTIVE' THEN CONCAT(member_id, ':', holding_id) ELSE NULL END) STORED,
    CONSTRAINT uk_borrowing_request UNIQUE (request_id),
    CONSTRAINT uk_borrowing_active_copy UNIQUE (active_copy_key),
    CONSTRAINT uk_borrowing_active_member UNIQUE (active_member_key),
    CONSTRAINT fk_bor_request FOREIGN KEY (request_id) REFERENCES borrow_requests (id),
    CONSTRAINT fk_bor_member FOREIGN KEY (member_id) REFERENCES users (id),
    CONSTRAINT fk_bor_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_bor_holding FOREIGN KEY (holding_id) REFERENCES library_holdings (id),
    CONSTRAINT fk_bor_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_bor_copy FOREIGN KEY (copy_id) REFERENCES book_copies (id),
    CONSTRAINT fk_bor_issuer FOREIGN KEY (issued_by) REFERENCES users (id),
    CONSTRAINT fk_bor_receiver FOREIGN KEY (returned_to) REFERENCES users (id),
    CONSTRAINT ck_bor_status CHECK (status IN ('ACTIVE', 'RETURNED', 'LOST')),
    CONSTRAINT ck_bor_condition CHECK (return_condition IS NULL OR return_condition IN ('GOOD', 'DAMAGED', 'LOST'))
);
CREATE INDEX idx_bor_library_status ON borrowings (library_id, status, due_date);
CREATE INDEX idx_bor_member_status ON borrowings (member_id, status, due_date);
CREATE INDEX idx_bor_book_issued ON borrowings (book_id, issued_at);
CREATE INDEX idx_bor_status_due ON borrowings (status, due_date);

CREATE TABLE fines (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id      BIGINT       NOT NULL,
    library_id     BIGINT       NOT NULL,
    borrowing_id   BIGINT,
    type           VARCHAR(16)  NOT NULL,
    amount_paise   BIGINT       NOT NULL,
    overdue_days   INT,
    status         VARCHAR(16)  NOT NULL,
    reason         VARCHAR(500),
    created_by     BIGINT,
    waived_by      BIGINT,
    waived_reason  VARCHAR(500),
    paid_at        DATETIME(6),
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_fine_borrowing_type UNIQUE (borrowing_id, type),
    CONSTRAINT fk_fine_member FOREIGN KEY (member_id) REFERENCES users (id),
    CONSTRAINT fk_fine_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_fine_borrowing FOREIGN KEY (borrowing_id) REFERENCES borrowings (id),
    CONSTRAINT fk_fine_creator FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT fk_fine_waiver FOREIGN KEY (waived_by) REFERENCES users (id),
    CONSTRAINT ck_fine_type CHECK (type IN ('OVERDUE', 'LOST', 'DAMAGED', 'MANUAL')),
    CONSTRAINT ck_fine_status CHECK (status IN ('UNPAID', 'PAID', 'WAIVED')),
    CONSTRAINT ck_fine_amount CHECK (amount_paise > 0)
);
CREATE INDEX idx_fine_member_status ON fines (member_id, status);
CREATE INDEX idx_fine_library_status ON fines (library_id, status, created_at);

CREATE TABLE payments (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    fine_id              BIGINT       NOT NULL,
    member_id            BIGINT       NOT NULL,
    library_id           BIGINT       NOT NULL,
    amount_paise         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL DEFAULT 'INR',
    provider             VARCHAR(16)  NOT NULL DEFAULT 'RAZORPAY',
    provider_order_id    VARCHAR(64)  NOT NULL,
    provider_payment_id  VARCHAR(64),
    method               VARCHAR(32),
    status               VARCHAR(16)  NOT NULL,
    verified_at          DATETIME(6),
    failure_reason       VARCHAR(500),
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_payment_order UNIQUE (provider_order_id),
    CONSTRAINT uk_payment_provider_id UNIQUE (provider_payment_id),
    CONSTRAINT fk_pay_fine FOREIGN KEY (fine_id) REFERENCES fines (id),
    CONSTRAINT fk_pay_member FOREIGN KEY (member_id) REFERENCES users (id),
    CONSTRAINT fk_pay_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT ck_pay_status CHECK (status IN ('CREATED', 'PAID', 'FAILED', 'EXPIRED'))
);
CREATE INDEX idx_pay_fine ON payments (fine_id, status);
CREATE INDEX idx_pay_member ON payments (member_id, created_at);
CREATE INDEX idx_pay_library ON payments (library_id, status, verified_at);

CREATE TABLE digital_resources (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    library_id         BIGINT       NOT NULL,
    book_id            BIGINT,
    title              VARCHAR(200) NOT NULL,
    description        VARCHAR(2000),
    type               VARCHAR(8)   NOT NULL,
    visibility         VARCHAR(20)  NOT NULL,
    storage_key        VARCHAR(255),
    original_filename  VARCHAR(255),
    external_url       VARCHAR(1000),
    mime_type          VARCHAR(100),
    size_bytes         BIGINT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    uploaded_by        BIGINT       NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_res_library FOREIGN KEY (library_id) REFERENCES libraries (id),
    CONSTRAINT fk_res_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_res_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id),
    CONSTRAINT ck_res_type CHECK (type IN ('PDF', 'EPUB', 'VIDEO', 'LINK')),
    CONSTRAINT ck_res_visibility CHECK (visibility IN ('PUBLIC', 'REGISTERED', 'LIBRARY_MEMBERS')),
    CONSTRAINT ck_res_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_res_source CHECK ((type = 'LINK' AND external_url IS NOT NULL)
        OR (type <> 'LINK' AND (storage_key IS NOT NULL OR external_url IS NOT NULL)))
);
CREATE INDEX idx_res_library ON digital_resources (library_id, status, created_at);
CREATE INDEX idx_res_book ON digital_resources (book_id, status);

CREATE TABLE notifications (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT        NOT NULL,
    library_id  BIGINT,
    type        VARCHAR(40)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(2000),
    action_url  VARCHAR(500),
    read_at     DATETIME(6),
    created_at  DATETIME(6)   NOT NULL,
    CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notif_library FOREIGN KEY (library_id) REFERENCES libraries (id)
);
CREATE INDEX idx_notif_user ON notifications (user_id, read_at, created_at);

CREATE TABLE email_verifications (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT      NOT NULL,
    otp_hash         CHAR(64)    NOT NULL,
    link_token_hash  CHAR(64)    NOT NULL,
    otp_expires_at   DATETIME(6) NOT NULL,
    link_expires_at  DATETIME(6) NOT NULL,
    attempt_count    INT         NOT NULL DEFAULT 0,
    status           VARCHAR(16) NOT NULL,
    sent_at          DATETIME(6) NOT NULL,
    consumed_at      DATETIME(6),
    consumed_via     VARCHAR(8),
    created_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_ev_link UNIQUE (link_token_hash),
    CONSTRAINT fk_ev_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_ev_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'SUPERSEDED', 'LOCKED'))
);
CREATE INDEX idx_ev_user_status ON email_verifications (user_id, status);
CREATE INDEX idx_ev_user_created ON email_verifications (user_id, created_at);

CREATE TABLE account_tokens (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT      NOT NULL,
    purpose       VARCHAR(16) NOT NULL,
    token_hash    CHAR(64)    NOT NULL,
    expires_at    DATETIME(6) NOT NULL,
    used_at       DATETIME(6),
    requested_ip  VARCHAR(64),
    created_at    DATETIME(6) NOT NULL,
    CONSTRAINT uk_account_token UNIQUE (token_hash),
    CONSTRAINT fk_at_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_at_purpose CHECK (purpose IN ('PASSWORD_RESET', 'INVITE'))
);
CREATE INDEX idx_at_user ON account_tokens (user_id, purpose, created_at);

CREATE TABLE refresh_tokens (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    token_hash      CHAR(64)    NOT NULL,
    family_id       CHAR(36)    NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    revoked_at      DATETIME(6),
    replaced_by_id  BIGINT,
    ip              VARCHAR(64),
    user_agent      VARCHAR(255),
    created_at      DATETIME(6) NOT NULL,
    CONSTRAINT uk_rt_hash UNIQUE (token_hash),
    CONSTRAINT fk_rt_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_rt_family ON refresh_tokens (family_id);
CREATE INDEX idx_rt_user ON refresh_tokens (user_id, revoked_at);

-- Append-only. In production, grant the application user only INSERT/SELECT on this table.
CREATE TABLE audit_logs (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    occurred_at    DATETIME(6)  NOT NULL,
    actor_user_id  BIGINT,
    actor_role     VARCHAR(20),
    actor_email    VARCHAR(254),
    library_id     BIGINT,
    action         VARCHAR(64)  NOT NULL,
    entity_type    VARCHAR(40),
    entity_id      VARCHAR(64),
    outcome        VARCHAR(16)  NOT NULL,
    ip             VARCHAR(64),
    user_agent     VARCHAR(255),
    request_id     VARCHAR(64),
    details        JSON
);
CREATE INDEX idx_audit_library ON audit_logs (library_id, occurred_at);
CREATE INDEX idx_audit_actor ON audit_logs (actor_user_id, occurred_at);
CREATE INDEX idx_audit_action ON audit_logs (action, occurred_at);
CREATE INDEX idx_audit_time ON audit_logs (occurred_at);

CREATE TABLE ai_conversations (
    id               CHAR(36)     PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    title            VARCHAR(200),
    created_at       DATETIME(6)  NOT NULL,
    last_message_at  DATETIME(6)  NOT NULL,
    CONSTRAINT fk_aic_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_aic_user ON ai_conversations (user_id, last_message_at);

CREATE TABLE ai_messages (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id  CHAR(36)     NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          TEXT,
    payload          JSON,
    latency_ms       INT,
    first_token_ms   INT,
    created_at       DATETIME(6)  NOT NULL,
    CONSTRAINT fk_aim_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversations (id) ON DELETE CASCADE,
    CONSTRAINT ck_aim_role CHECK (role IN ('USER', 'MODEL', 'TOOL_CALL', 'TOOL_RESULT'))
);
CREATE INDEX idx_aim_conversation ON ai_messages (conversation_id, id);
CREATE INDEX idx_aim_created ON ai_messages (created_at);

CREATE TABLE email_outbox (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    to_email         VARCHAR(254)  NOT NULL,
    subject          VARCHAR(255)  NOT NULL,
    html_body        MEDIUMTEXT    NOT NULL,
    text_body        TEXT          NOT NULL,
    status           VARCHAR(16)   NOT NULL,
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(6)   NOT NULL,
    last_error       VARCHAR(500),
    sent_at          DATETIME(6),
    created_at       DATETIME(6)   NOT NULL,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX idx_outbox_due ON email_outbox (status, next_attempt_at);

CREATE TABLE reminder_log (
    borrowing_id   BIGINT      NOT NULL,
    reminder_type  VARCHAR(16) NOT NULL,
    sent_on        DATE        NOT NULL,
    PRIMARY KEY (borrowing_id, reminder_type, sent_on),
    CONSTRAINT fk_rl_borrowing FOREIGN KEY (borrowing_id) REFERENCES borrowings (id)
);

CREATE TABLE platform_settings (
    setting_key    VARCHAR(64)   PRIMARY KEY,
    setting_value  VARCHAR(1000) NOT NULL,
    updated_by     BIGINT,
    updated_at     DATETIME(6)   NOT NULL
);
