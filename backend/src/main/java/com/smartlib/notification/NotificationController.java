package com.smartlib.notification;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.web.PageResponse;
import com.smartlib.security.CurrentUser;

/** Notifications for every role, always limited to the caller's own. */
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public PageResponse<NotificationService.NotificationView> list(@RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size) {
        return notifications.list(CurrentUser.require().userId(), unreadOnly, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", notifications.unreadCount(CurrentUser.require().userId()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id) {
        notifications.markRead(CurrentUser.require().userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", notifications.markAllRead(CurrentUser.require().userId()));
    }
}
