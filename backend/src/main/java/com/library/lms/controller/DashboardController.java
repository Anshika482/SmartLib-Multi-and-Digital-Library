package com.library.lms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.DashboardResponse;
import com.library.lms.service.DashboardService;

/**
 * What an account sees when it opens the application.
 *
 * <p>One endpoint for every role. There is no role in the URL and no parameter
 * to widen: the caller comes from the filter chain, and
 * {@code DashboardService} decides from their account which figures to compute.
 * A member asking for this gets a member's dashboard because that is the only
 * one the service builds for them.</p>
 *
 * <p>Counts and sums only. The screens behind each panel are separate
 * endpoints, each with its own authorization.</p>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** GET /api/dashboard - the caller's own dashboard. */
    @GetMapping
    public ResponseEntity<DashboardResponse> dashboard(Authentication authentication) {
        return ResponseEntity.ok(dashboardService.forUser(authentication.getName()));
    }
}
