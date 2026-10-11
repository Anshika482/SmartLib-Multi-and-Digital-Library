package com.smartlib.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.web.PageResponse;
import com.smartlib.security.CurrentUser;

/** SUPER_ADMIN only (secured with the rest of /api/platform/**): review ADMIN and SUPER_ADMIN applications. */
@RestController
@RequestMapping("/api/platform/applications")
public class PlatformApplicationController {

    public record RejectRequest(@NotBlank(message = "Please give a reason") @Size(max = 500, message = "Reason is too long (max 500 characters)") String reason) {
    }

    private final PlatformApplicationService applications;

    public PlatformApplicationController(PlatformApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public PageResponse<PlatformApplicationService.AdminApplication> list(@RequestParam(required = false) String role,
                                                                          @RequestParam(required = false) String status,
                                                                          @RequestParam(required = false) String q,
                                                                          @RequestParam(required = false) Integer page,
                                                                          @RequestParam(required = false) Integer size) {
        return applications.list(role, status, q, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(@PathVariable long id) {
        applications.approve(id, CurrentUser.require().userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable long id, @Valid @RequestBody RejectRequest req) {
        applications.reject(id, req.reason(), CurrentUser.require().userId());
        return ResponseEntity.noContent().build();
    }
}
