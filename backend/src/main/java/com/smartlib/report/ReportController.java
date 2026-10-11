package com.smartlib.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.audit.AuditQueryService;
import com.smartlib.circulation.CirculationDtos.LoanView;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.circulation.CirculationQueries;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.util.Money;
import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineDtos;
import com.smartlib.fine.FineService;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.notification.NotificationService;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.LibraryScope;
import com.smartlib.security.SmartLibPrincipal;

@RestController
public class ReportController {

    private final ReportService reports;
    private final CirculationQueries circulation;
    private final FineService fines;
    private final NotificationService notifications;
    private final AuditQueryService audit;
    private final LibraryRepository libraries;

    public ReportController(ReportService reports, CirculationQueries circulation, FineService fines, NotificationService notifications,
                            AuditQueryService audit, LibraryRepository libraries) {
        this.reports = reports;
        this.circulation = circulation;
        this.fines = fines;
        this.notifications = notifications;
        this.audit = audit;
        this.libraries = libraries;
    }

    public record LibraryInfo(long id, String name, String slug, String city, String code) {
    }

    public record LibraryDashboard(LibraryInfo library, ReportService.LibrarySummary summary, List<RequestView> pendingRequests,
                                   List<LoanView> overdue, List<AuditQueryService.AuditEntry> recentActivity,
                                   List<ReportService.MonthPoint> trends) {
    }

    public record MemberDashboard(long activeLoans, long dueSoon, long overdue, long pendingRequests, long readyForPickup,
                                  long unpaidFinesCount, long unpaidFinesPaise, long unreadNotifications, List<LoanView> loans,
                                  List<RequestView> requests, List<NotificationService.NotificationView> notifications) {
    }

    // ---------- member ----------

    @GetMapping("/api/me/dashboard")
    public MemberDashboard memberDashboard() {
        SmartLibPrincipal me = CurrentUser.requireMember();
        PageResponse<LoanView> loans = circulation.memberLoans(me.userId(), "ACTIVE", 0, 50);
        PageResponse<RequestView> requests = circulation.memberRequests(me.userId(), "ACTIVE", null, 0, 50);
        FineDtos.FineSummary fineSummary = fines.memberSummary(me.userId());
        long dueSoon = loans.content().stream().filter(LoanView::dueSoon).count();
        long overdue = loans.content().stream().filter(LoanView::overdue).count();
        long pending = requests.content().stream().filter(r -> r.status().name().equals("PENDING")).count();
        long ready = requests.content().stream().filter(r -> r.status().name().equals("APPROVED")).count();
        return new MemberDashboard(loans.totalElements(), dueSoon, overdue, pending, ready, fineSummary.unpaidCount(), fineSummary.unpaidPaise(),
                notifications.unreadCount(me.userId()), loans.content().stream().limit(4).toList(),
                requests.content().stream().limit(4).toList(), notifications.list(me.userId(), false, 0, 5).content());
    }

    // ---------- library ----------

    @GetMapping("/api/manage/libraries/{libraryId}/dashboard")
    public LibraryDashboard libraryDashboard(LibraryScope scope) {
        Library l = libraries.findById(scope.libraryId()).orElseThrow();
        return new LibraryDashboard(new LibraryInfo(l.getId(), l.getName(), l.getSlug(), l.getCity(), l.getCode()),
                reports.librarySummary(scope.libraryId()),
                circulation.libraryRequests(scope.libraryId(), "PENDING", null, 0, 6).content(),
                circulation.libraryLoans(scope.libraryId(), "OVERDUE", null, 0, 5).content(),
                audit.search(scope.libraryId(), null, null, null, null, null, 0, 8).content(),
                reports.monthlyTrends(scope.libraryId(), 6));
    }

    @GetMapping("/api/manage/libraries/{libraryId}/reports/summary")
    public ReportService.LibrarySummary summary(LibraryScope scope) {
        return reports.librarySummary(scope.libraryId());
    }

    @GetMapping("/api/manage/libraries/{libraryId}/reports/popular-books")
    public List<ReportService.PopularBook> popular(LibraryScope scope, @RequestParam(required = false) Integer days,
                                                   @RequestParam(required = false) Integer limit) {
        return reports.popularBooks(scope.libraryId(), clampDays(days), PageResponse.clampSize(limit, 10, 50));
    }

    @GetMapping("/api/manage/libraries/{libraryId}/reports/trends")
    public List<ReportService.MonthPoint> trends(LibraryScope scope, @RequestParam(required = false) Integer months) {
        return reports.monthlyTrends(scope.libraryId(), clampMonths(months));
    }

    @GetMapping("/api/manage/libraries/{libraryId}/reports/categories")
    public List<ReportService.CategoryPoint> categories(LibraryScope scope) {
        return reports.categories(scope.libraryId());
    }

    @GetMapping("/api/manage/libraries/{libraryId}/reports/export.csv")
    public ResponseEntity<String> export(LibraryScope scope, @RequestParam String type) {
        long lib = scope.libraryId();
        String csv = switch (type) {
            case "popular" -> CsvWriter.write(List.of("Title", "Authors", "ISBN-13", "Loans (90 days)"),
                    reports.popularBooks(lib, 90, 100).stream().map(p -> List.<Object>of(p.book().title(), String.join("; ", p.book().authors()),
                            p.book().isbn13() == null ? "" : p.book().isbn13(), p.loans())).toList());
            case "overdue" -> CsvWriter.write(List.of("Member", "Email", "Title", "Copy", "Due date", "Days overdue", "Estimated fine"),
                    circulation.libraryLoans(lib, "OVERDUE", null, 0, 1000).content().stream().map(l -> List.<Object>of(l.member().fullName(),
                            l.member().email(), l.book().title(), l.accessionNo(), l.dueDate(), l.overdueDays(), Money.inr(l.estimatedFinePaise()))).toList());
            case "fines" -> CsvWriter.write(List.of("Member", "Email", "Type", "Amount", "Status", "Reason", "Created"),
                    fines.libraryFines(scope, "ALL", null, 0, 1000).content().stream().map(f -> List.<Object>of(f.member().fullName(),
                            f.member().email(), f.type(), Money.inr(f.amountPaise()), f.status(), f.reason() == null ? "" : f.reason(),
                            f.createdAt())).toList());
            case "loans" -> CsvWriter.write(List.of("Member", "Email", "Title", "Copy", "Issued", "Due", "Returned", "Status"),
                    circulation.libraryLoans(lib, "ALL", null, 0, 2000).content().stream().map(l -> {
                        List<Object> row = new ArrayList<>();
                        row.add(l.member().fullName());
                        row.add(l.member().email());
                        row.add(l.book().title());
                        row.add(l.accessionNo());
                        row.add(l.issuedAt());
                        row.add(l.dueDate());
                        row.add(l.returnedAt() == null ? "" : l.returnedAt());
                        row.add(l.status());
                        return row;
                    }).toList());
            default -> throw ApiException.validation("type", "Unknown report type");
        };
        return csv(csv, "smartlib-" + type + "-" + LocalDate.now() + ".csv");
    }

    // ---------- platform ----------

    @GetMapping("/api/platform/analytics/overview")
    public ReportService.PlatformOverview platformOverview() {
        return reports.platformOverview();
    }

    @GetMapping("/api/platform/analytics/libraries")
    public List<ReportService.LibraryRow> libraryComparison() {
        return reports.libraryComparison();
    }

    @GetMapping("/api/platform/analytics/trends")
    public List<ReportService.MonthPoint> platformTrends(@RequestParam(required = false) Integer months) {
        return reports.monthlyTrends(null, clampMonths(months));
    }

    @GetMapping("/api/platform/analytics/popular-books")
    public List<ReportService.PopularBook> platformPopular(@RequestParam(required = false) Integer days,
                                                           @RequestParam(required = false) Integer limit) {
        return reports.popularBooks(null, clampDays(days), PageResponse.clampSize(limit, 10, 50));
    }

    @GetMapping("/api/platform/analytics/categories")
    public List<ReportService.CategoryPoint> platformCategories() {
        return reports.categories(null);
    }

    @GetMapping("/api/platform/analytics/export.csv")
    public ResponseEntity<String> platformExport(@RequestParam String type) {
        if (!"libraries".equals(type)) {
            throw ApiException.validation("type", "Unknown report type");
        }
        String csv = CsvWriter.write(List.of("Library", "Code", "City", "Status", "Titles", "Copies", "Active loans", "Overdue", "Members",
                        "Pending requests", "Fines collected", "Staff"),
                reports.libraryComparison().stream().map(r -> List.<Object>of(r.name(), r.code(), r.city() == null ? "" : r.city(), r.status(),
                        r.titles(), r.copies(), r.activeLoans(), r.overdueLoans(), r.members(), r.pendingRequests(),
                        Money.inr(r.finesCollectedPaise()), r.staff())).toList());
        return csv(csv, "smartlib-libraries-" + LocalDate.now() + ".csv");
    }

    // ---------- audit ----------

    @GetMapping("/api/manage/libraries/{libraryId}/audit-logs")
    public PageResponse<AuditQueryService.AuditEntry> libraryAudit(LibraryScope scope, @RequestParam(required = false) String action,
                                                                   @RequestParam(required = false) String q,
                                                                   @RequestParam(required = false) String outcome,
                                                                   @RequestParam(required = false) LocalDate from,
                                                                   @RequestParam(required = false) LocalDate to,
                                                                   @RequestParam(required = false) Integer page,
                                                                   @RequestParam(required = false) Integer size) {
        scope.requireAdmin();
        return audit.search(scope.libraryId(), action, q, outcome, from, to, PageResponse.clampPage(page), PageResponse.clampSize(size, 25, 100));
    }

    @GetMapping("/api/platform/audit-logs")
    public PageResponse<AuditQueryService.AuditEntry> platformAudit(@RequestParam(required = false) Long libraryId,
                                                                    @RequestParam(required = false) String action,
                                                                    @RequestParam(required = false) String q,
                                                                    @RequestParam(required = false) String outcome,
                                                                    @RequestParam(required = false) LocalDate from,
                                                                    @RequestParam(required = false) LocalDate to,
                                                                    @RequestParam(required = false) Integer page,
                                                                    @RequestParam(required = false) Integer size) {
        return audit.search(libraryId, action, q, outcome, from, to, PageResponse.clampPage(page), PageResponse.clampSize(size, 25, 100));
    }

    private static int clampDays(Integer days) {
        return days == null || days <= 0 ? 90 : Math.min(days, 3650);
    }

    private static int clampMonths(Integer months) {
        return months == null || months <= 0 ? 6 : Math.min(months, 24);
    }

    private static ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body("﻿" + body);
    }
}
