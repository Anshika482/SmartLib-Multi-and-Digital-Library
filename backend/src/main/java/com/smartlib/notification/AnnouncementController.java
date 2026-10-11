package com.smartlib.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}/announcements")
public class AnnouncementController {

    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final NotificationService notifications;

    public AnnouncementController(ApplicationEventPublisher events, AuditService audit, NotificationService notifications) {
        this.events = events;
        this.audit = audit;
        this.notifications = notifications;
    }

    public record AnnouncementRequest(@NotBlank(message = "Title is required") @Size(max = 150) String title,
                                      @NotBlank(message = "Message is required") @Size(max = 1500) String body,
                                      boolean sendEmail) {
    }

    public record AnnouncementResult(int recipients) {
    }

    @PostMapping
    @Transactional
    public AnnouncementResult send(LibraryScope scope, @Valid @RequestBody AnnouncementRequest req) {
        int recipients = notifications.membersOf(scope.libraryId()).size();
        audit.record(AuditActions.ANNOUNCEMENT_SENT, "LIBRARY", scope.libraryId(), scope.libraryId(),
                AuditService.details("title", req.title(), "recipients", recipients, "email", req.sendEmail()));
        events.publishEvent(new DomainEvents.Announcement(scope.libraryId(), req.title().trim(), req.body().trim(), req.sendEmail(), scope.actorId()));
        return new AnnouncementResult(recipients);
    }
}
