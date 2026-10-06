package com.library.lms.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.PendingRegistrationResponse;
import com.library.lms.dto.RegistrationRequest;
import com.library.lms.dto.RegistrationResponse;
import com.library.lms.service.RegistrationRateLimiter;
import com.library.lms.service.RegistrationService;

/**
 * Registering, and deciding on registrations.
 *
 * <p>Two halves with opposite audiences, which is why they are one class: the
 * public one is {@code POST /api/auth/register}, and the rest is for whoever
 * decides. Keeping them together makes the boundary between them readable in a
 * single file, and the filter chain enforces it either way.
 *
 * <p><b>Nothing here reads a role from a request.</b> The registration endpoint
 * takes a registration type and {@code RegistrationService} maps it; the
 * decision endpoints take the caller identity from the filter chain, never from
 * the body. There is no field in any request that names an authority.
 *
 * <p>The public endpoint is rate limited per client address. The decision
 * endpoints are not: reaching them already required authentication and a role.
 */
@RestController
@RequestMapping("/api")
public class RegistrationController {

    private final RegistrationService registrationService;

    private final RegistrationRateLimiter rateLimiter;

    public RegistrationController(RegistrationService registrationService, RegistrationRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * POST /api/auth/register - registers a member, or records an application.
     *
     * <p>201 with the status and a sentence for the person. A member is approved
     * and may sign in; a librarian or administrator is pending until somebody
     * decides. No token is issued either way - registering is not a way to
     * become authenticated in one step.
     *
     * <p>429 when a client has registered too often, before anything is written.
     */
    @PostMapping("/auth/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request,
            HttpServletRequest httpRequest) {
        String client = httpRequest.getRemoteAddr();

        if (!rateLimiter.tryAcquire(client)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.secondsUntilReset(client)))
                    .build();
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
    }

    /**
     * GET /api/registrations/pending - what this caller may decide.
     *
     * <p>An administrator gets librarian applications to their own library; a
     * super administrator gets administrator applications. The scoping is done
     * in the service from the authenticated account, so there is no parameter
     * here to widen.
     */
    @GetMapping("/registrations/pending")
    public ResponseEntity<List<PendingRegistrationResponse>> pending(Authentication authentication) {
        return ResponseEntity.ok(registrationService.pending(authentication.getName()));
    }

    /** POST /api/registrations/{userId}/approve - the account becomes usable. */
    @PostMapping("/registrations/{userId}/approve")
    public ResponseEntity<PendingRegistrationResponse> approve(@PathVariable Long userId,
            Authentication authentication) {
        return ResponseEntity.ok(registrationService.approve(userId, authentication.getName()));
    }

    /** POST /api/registrations/{userId}/reject - the account stays disabled. */
    @PostMapping("/registrations/{userId}/reject")
    public ResponseEntity<PendingRegistrationResponse> reject(@PathVariable Long userId,
            Authentication authentication) {
        return ResponseEntity.ok(registrationService.reject(userId, authentication.getName()));
    }
}
