package com.library.lms.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.repository.Repository;

import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.NotificationLog;

/**
 * Data access for {@link NotificationLog}: claim a notification, and read one
 * library's.
 *
 * <p>A bare {@link Repository} rather than {@code JpaRepository}, like
 * {@code AuditEventRepository}: there is no update and no delete here, because a
 * record of something already sent is not something to revise.</p>
 *
 * <p>The reads are scoped by library, as everywhere else. The existence check is
 * scoped by recipient and subject instead, which is narrower still - it asks
 * about one person and one thing.</p>
 */
@org.springframework.stereotype.Repository
public interface NotificationLogRepository extends Repository<NotificationLog, Long> {

    /**
     * Records a notification, or fails because it is already recorded.
     *
     * <p>The unique key over kind, subject and recipient is what makes the
     * second attempt fail rather than duplicate - so this is the claim, not
     * merely a log line.</p>
     */
    NotificationLog save(NotificationLog notification);

    /**
     * Whether this person has already been told this about this thing.
     *
     * <p>Used as a cheap check before attempting the insert. It is not the
     * guarantee - two threads could both read false - which is why the unique
     * key exists as well, and why the caller treats a constraint violation as a
     * normal outcome rather than an error.</p>
     */
    boolean existsByKindAndSubjectIdAndRecipientUserId(NotificationKind kind, Long subjectId,
            Long recipientUserId);

    /** One library's notifications in a window, for the tests and any future screen. */
    List<NotificationLog> findByLibraryIdAndCreatedAtBetweenOrderByIdDesc(Long libraryId,
            LocalDateTime from, LocalDateTime to);

    /** How many of a library's notifications are of one kind. */
    long countByLibraryIdAndKind(Long libraryId, NotificationKind kind);
}
