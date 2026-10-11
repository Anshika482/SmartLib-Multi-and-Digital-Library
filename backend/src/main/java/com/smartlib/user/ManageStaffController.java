package com.smartlib.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.web.PageResponse;
import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}")
public class ManageStaffController {

    private final StaffService staff;

    public ManageStaffController(StaffService staff) {
        this.staff = staff;
    }

    public record RejectRequest(@NotBlank(message = "Please give a reason") @Size(max = 500) String reason) {
    }

    public record MembershipStatusRequest(@NotNull MembershipStatus status) {
    }

    @GetMapping("/librarian-applications")
    public PageResponse<StaffService.Application> applications(LibraryScope scope, @RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String q,
                                                               @RequestParam(required = false) Integer page,
                                                               @RequestParam(required = false) Integer size) {
        scope.requireAdmin();
        return staff.applications(scope, status, "LIBRARIAN", q, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/librarian-applications/{assignmentId}/approve")
    public ResponseEntity<Void> approve(LibraryScope scope, @PathVariable long assignmentId) {
        staff.approve(scope, assignmentId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/librarian-applications/{assignmentId}/reject")
    public ResponseEntity<Void> reject(LibraryScope scope, @PathVariable long assignmentId, @Valid @RequestBody RejectRequest req) {
        staff.reject(scope, assignmentId, req.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/staff")
    public PageResponse<StaffService.Application> staffList(LibraryScope scope, @RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return staff.applications(scope, "APPROVED", null, null, PageResponse.clampPage(page), PageResponse.clampSize(size, 50, 100));
    }

    @PostMapping("/staff/{userId}/revoke")
    public ResponseEntity<Void> revoke(LibraryScope scope, @PathVariable long userId) {
        staff.revoke(scope, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    public PageResponse<StaffService.MemberRow> members(LibraryScope scope, @RequestParam(required = false) String q,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return staff.members(scope, q, status, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PatchMapping("/members/{userId}/membership")
    public ResponseEntity<Void> setMembership(LibraryScope scope, @PathVariable long userId,
                                              @Valid @RequestBody MembershipStatusRequest req) {
        staff.setMembershipStatus(scope, userId, req.status());
        return ResponseEntity.noContent().build();
    }
}
