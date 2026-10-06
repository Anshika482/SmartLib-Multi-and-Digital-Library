package com.library.lms.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.ReportResponse;
import com.library.lms.service.ReportService;

/**
 * What a library, or the whole deployment, actually did.
 *
 * <p>One endpoint, and the caller's role decides its scope: there is no library
 * parameter here and none accepted. A super administrator gets every library, a
 * librarian or administrator gets their own, and a member is refused - by the
 * filter chain first and by {@link ReportService} again.</p>
 *
 * <p>The dates are the only thing a caller supplies, and the service checks
 * them before it counts anything.</p>
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * GET /api/reports?from=2026-01-01&to=2026-03-31
     *
     * <p>Both dates are required and inclusive. An inverted or over-long range
     * is a 400 naming the problem, rather than a report full of zeroes that
     * reads as a quiet library.</p>
     */
    @GetMapping
    public ResponseEntity<ReportResponse> report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return ResponseEntity.ok(reportService.report(from, to, authentication.getName()));
    }
}
