package com.library.lms.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One notification this library has already sent.
 *
 * <p><b>This table is what makes notifications idempotent</b>, and it is the
 * only reason it exists. The unique key over kind, subject and recipient means a
 * second attempt to tell somebody the same thing about the same loan cannot be
 * inserted - so a retried issue, a replayed payment callback or a reminder sweep
 * that runs twice all produce one message rather than several. The row is
 * written <i>before</i> the message is handed to the mail server, because the
 * question it answers is "have we already decided to tell them?", not "did the
 * SMTP server accept it?".
 *
 * <p><b>What it deliberately does not hold.</b> No address, no subject line and
 * no body. The address belongs to the account and is read when the message is
 * built; keeping a copy here would mean a second place to update when somebody
 * changes it, and a second place for it to leak. The content is reconstructable
 * from the kind and the subject, and a table of message bodies is a table of
 * everything the library has ever told anybody.
 *
 * <p>{@code subjectId} is whatever the kind is about: a loan for
 * {@code BOOK_ISSUED}, a request for {@code REQUEST_APPROVED}, the account
 * itself for a registration decision. It is not a foreign key, because it points
 * at different tables depending on the kind - and because a notification should
 * outlive the row it describes rather than be deleted with it.
 */
@Entity
@Table(
        name = "notification_log",
        // The idempotency rule, enforced by the database rather than by a check
        // somebody could forget. Named so the migration and the mappings agree,
        // which FlywayMigrationIntegrationTest compares fact for fact.
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_log_once",
                columnNames = {"kind", "subject_id", "recipient_user_id"}),
        indexes = {
                @Index(name = "idx_notification_log_library_sent",
                        columnList = "library_id, created_at"),
                @Index(name = "idx_notification_log_recipient",
                        columnList = "recipient_user_id, created_at")
        })
@Getter
@Setter
@ToString
@NoArgsConstructor
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The library the event happened in.
     *
     * <p>Every read of this table is scoped by it, like every other table in
     * this application. A notification belongs to one library even though the
     * message went to one person.</p>
     */
    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_notification_log_library"))
    private Library library;

    /**
     * Who it was sent to, by id.
     *
     * <p>An id rather than a relationship, as {@code AuditEvent} records its
     * actor: loading an account to write this row would drag a password hash
     * along behind it, and the notification is a fact about a moment rather than
     * about the account as it is now.</p>
     */
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private NotificationKind kind;

    /** The loan, request or account the notification is about. Never a foreign key - see the class note. */
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Whether it reached the mail server. Updated after the attempt; the row exists either way. */
    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private NotificationOutcome outcome;
}
