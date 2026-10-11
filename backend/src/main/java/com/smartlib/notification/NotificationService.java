package com.smartlib.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;

@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final JdbcTemplate jdbc;

    public NotificationService(NotificationRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    public record NotificationView(long id, String type, String title, String body, String actionUrl, Long libraryId,
                                   boolean read, Instant createdAt) {
        static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getActionUrl(), n.getLibraryId(),
                    n.getReadAt() != null, n.getCreatedAt());
        }
    }

    @Transactional
    public void notify(long userId, Long libraryId, String type, String title, String body, String actionUrl) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setLibraryId(libraryId);
        n.setType(type);
        n.setTitle(Texts.truncate(title, 200));
        n.setBody(Texts.truncate(body, 2000));
        n.setActionUrl(actionUrl);
        n.setCreatedAt(Instant.now());
        repository.save(n);
    }

    @Transactional
    public void notifyAll(Collection<Long> userIds, Long libraryId, String type, String title, String body, String actionUrl) {
        if (userIds.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        jdbc.batchUpdate("INSERT INTO notifications (user_id, library_id, type, title, body, action_url, created_at) VALUES (?,?,?,?,?,?,?)",
                userIds.stream().map(id -> new Object[]{id, libraryId, type, Texts.truncate(title, 200), Texts.truncate(body, 2000),
                        actionUrl, java.sql.Timestamp.from(now)}).toList());
    }

    public PageResponse<NotificationView> list(long userId, boolean unreadOnly, int page, int size) {
        Page<Notification> p = unreadOnly
                ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                : repository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size));
        return PageResponse.of(p, NotificationView::of);
    }

    public long unreadCount(long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(long userId, long id) {
        Notification n = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Notification not found."));
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repository.save(n);
        }
    }

    @Transactional
    public int markAllRead(long userId) {
        return repository.markAllRead(userId, Instant.now());
    }

    /** Approved staff (librarians + admins) of a library. */
    public List<Long> staffOf(long libraryId) {
        return jdbc.queryForList("SELECT s.user_id FROM staff_assignments s JOIN users u ON u.id = s.user_id"
                + " WHERE s.library_id = ? AND s.approval_status = 'APPROVED' AND u.status = 'ACTIVE'", Long.class, libraryId);
    }

    public List<Long> adminsOf(long libraryId) {
        List<Long> admins = jdbc.queryForList("SELECT s.user_id FROM staff_assignments s JOIN users u ON u.id = s.user_id"
                + " WHERE s.library_id = ? AND s.approval_status = 'APPROVED' AND u.role = 'ADMIN' AND u.status = 'ACTIVE'", Long.class, libraryId);
        if (!admins.isEmpty()) {
            return admins;
        }
        return superAdmins();
    }

    /** Active, verified SUPER_ADMINs who can sign in; pending or rejected SUPER_ADMIN applicants are excluded. */
    public List<Long> superAdmins() {
        return jdbc.queryForList("SELECT u.id FROM users u LEFT JOIN staff_assignments s ON s.user_id = u.id"
                + " WHERE u.role = 'SUPER_ADMIN' AND u.status = 'ACTIVE' AND u.email_verified_at IS NOT NULL"
                + " AND (s.id IS NULL OR s.approval_status = 'APPROVED')", Long.class);
    }

    public List<Long> membersOf(long libraryId) {
        return jdbc.queryForList("SELECT m.user_id FROM library_memberships m JOIN users u ON u.id = m.user_id"
                + " WHERE m.library_id = ? AND m.status = 'ACTIVE' AND u.status = 'ACTIVE'", Long.class, libraryId);
    }
}
