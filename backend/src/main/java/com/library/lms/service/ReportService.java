package com.library.lms.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.ReportResponse;
import com.library.lms.dto.ReportResponse.CategoryCount;
import com.library.lms.dto.ReportResponse.MonthMoney;
import com.library.lms.dto.ReportResponse.MonthPoint;
import com.library.lms.dto.ReportResponse.TitleCount;
import com.library.lms.dto.ReportResponse.Totals;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.PaymentStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.InvalidReportRangeException;
import com.library.lms.exception.ReportAccessDeniedException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.PaymentRepository;
import com.library.lms.repository.ReportRepository;
import com.library.lms.repository.ReportRepository.MonthCount;
import com.library.lms.repository.UserRepository;

/**
 * What a library, or a whole deployment, actually did.
 *
 * <p><b>Two scopes, chosen by role and never by request.</b> A super
 * administrator gets the deployment; an administrator or librarian gets their
 * own library, taken from their account. There is no library parameter on any
 * method here, so there is nothing for a caller to substitute, and the two sets
 * of queries are separate methods with different names rather than one query
 * with a nullable condition - a report that could quietly widen its own scope
 * is the failure this arrangement exists to prevent.
 *
 * <p><b>Members get no report.</b> Refused here as well as by the filter chain:
 * a report is about everybody's borrowing, and a member is entitled to their
 * own. Their own figures are on their loans and fines screens.
 *
 * <p><b>Every figure is one aggregate query.</b> Nothing loads loans and counts
 * them in Java, so a report over ten years costs what a report over a week
 * costs, and no row of borrowing history is ever materialised to be summed.
 *
 * <p>Read-only throughout, and there is no method here that writes.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    /** How many rows a breakdown returns. Enough to be useful, bounded so it cannot be a dump. */
    private static final int BREAKDOWN_LIMIT = 10;

    /** The longest range a single report may cover, in days. Roughly five years. */
    private static final long MAX_RANGE_DAYS = 1830;

    private final ReportRepository reportRepository;

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    private final PaymentRepository paymentRepository;

    private final OverduePolicy overduePolicy;

    public ReportService(ReportRepository reportRepository, BookRepository bookRepository,
            UserRepository userRepository, PaymentRepository paymentRepository, OverduePolicy overduePolicy) {
        this.reportRepository = reportRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.overduePolicy = overduePolicy;
    }

    /**
     * The report for whoever is asking, over the range they asked for.
     *
     * @param from                  the first day to count, inclusive
     * @param to                    the last day to count, inclusive
     * @param authenticatedUsername the caller, from the filter chain - never from a request body
     * @throws ReportAccessDeniedException if the caller is a member
     * @throws InvalidReportRangeException if the dates are missing, inverted or too far apart
     */
    public ReportResponse report(LocalDate from, LocalDate to, String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        Role role = caller.getRole();

        // The service half of the two locks. Checked before the dates, so a
        // member gets the same refusal whatever they sent - validating first
        // would turn an authorization failure into a complaint about dates and
        // tell them their range was the only problem.
        if (role != Role.ROLE_LIBRARIAN && role != Role.ROLE_ADMIN && role != Role.ROLE_SUPER_ADMIN) {
            throw new ReportAccessDeniedException();
        }

        validateRange(from, to);

        boolean systemWide = role == Role.ROLE_SUPER_ADMIN;
        Long libraryId = caller.getLibrary() == null ? null : caller.getLibrary().getId();

        if (!systemWide && libraryId == null) {
            // Staff with no library have nothing to report on. Refused rather
            // than answered with the deployment's figures.
            throw new ReportAccessDeniedException();
        }

        return systemWide
                ? systemWideReport(role, from, to)
                : libraryReport(role, caller.getLibrary().getName(), libraryId, from, to);
    }

    // ---------- one library ----------

    private ReportResponse libraryReport(Role role, String libraryName, Long libraryId,
            LocalDate from, LocalDate to) {
        LocalDate today = overduePolicy.today();
        var page = PageRequest.of(0, BREAKDOWN_LIMIT);

        Totals totals = new Totals(
                bookRepository.countByLibraryId(libraryId),
                userRepository.countByLibraryIdAndRole(libraryId, Role.ROLE_MEMBER),
                reportRepository.countIssuesAndLibraryId(from, to, libraryId),
                reportRepository.countReturnsAndLibraryId(from, to, libraryId),
                reportRepository.countActiveLoansAndLibraryId(OverduePolicy.OPEN_STATUSES, libraryId),
                reportRepository.countOverdueAndLibraryId(OverduePolicy.OPEN_STATUSES, today, libraryId),
                reportRepository.sumFinesRaisedAndLibraryId(from, to, libraryId),
                reportRepository.sumFinesPaidAndLibraryId(
                        FinePaymentStatus.PAID, startOf(from), endOf(to), libraryId),
                reportRepository.sumOutstandingAndLibraryId(FinePaymentStatus.UNPAID, libraryId),
                paymentRepository.sumTakenAndLibraryId(
                        PaymentStatus.SUCCEEDED, startOf(from), endOf(to), libraryId));

        return new ReportResponse(
                role,
                false,
                libraryName,
                from,
                to,
                totals,
                titles(reportRepository.mostIssuedAndLibraryId(from, to, libraryId, page)),
                categories(reportRepository.popularCategoriesAndLibraryId(from, to, libraryId, page)),
                circulation(
                        reportRepository.issuesByMonthAndLibraryId(from, to, libraryId),
                        reportRepository.returnsByMonthAndLibraryId(from, to, libraryId)),
                overdue(reportRepository.overdueByMonthAndLibraryId(from, to, libraryId)));
    }

    // ---------- every library ----------

    private ReportResponse systemWideReport(Role role, LocalDate from, LocalDate to) {
        LocalDate today = overduePolicy.today();
        var page = PageRequest.of(0, BREAKDOWN_LIMIT);

        Totals totals = new Totals(
                bookRepository.count(),
                userRepository.countByRole(Role.ROLE_MEMBER),
                reportRepository.countIssuesSystemWide(from, to),
                reportRepository.countReturnsSystemWide(from, to),
                reportRepository.countActiveLoansSystemWide(OverduePolicy.OPEN_STATUSES),
                reportRepository.countOverdueSystemWide(OverduePolicy.OPEN_STATUSES, today),
                reportRepository.sumFinesRaisedSystemWide(from, to),
                reportRepository.sumFinesPaidSystemWide(FinePaymentStatus.PAID, startOf(from), endOf(to)),
                reportRepository.sumOutstandingSystemWide(FinePaymentStatus.UNPAID),
                paymentRepository.sumTakenSystemWide(PaymentStatus.SUCCEEDED, startOf(from), endOf(to)));

        return new ReportResponse(
                role,
                true,
                // No library name: this report is not about one.
                null,
                from,
                to,
                totals,
                titles(reportRepository.mostIssuedSystemWide(from, to, page)),
                categories(reportRepository.popularCategoriesSystemWide(from, to, page)),
                circulation(
                        reportRepository.issuesByMonthSystemWide(from, to),
                        reportRepository.returnsByMonthSystemWide(from, to)),
                overdue(reportRepository.overdueByMonthSystemWide(from, to)));
    }

    // ---------- helpers ----------

    /**
     * The dates, checked before anything is counted.
     *
     * <p>An inverted range would silently answer zero for everything, which
     * reads as "your library did nothing" rather than "you asked backwards".
     * The ceiling is there because a range is the only thing a caller controls
     * about how much work the database does.</p>
     */
    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidReportRangeException("A report needs a start date and an end date.");
        }
        if (to.isBefore(from)) {
            throw new InvalidReportRangeException("The end date must not be before the start date.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new InvalidReportRangeException(
                    "A report can cover at most " + MAX_RANGE_DAYS + " days. Choose a shorter range.");
        }
    }

    /** Midnight at the start of a day, for the columns that store a time as well. */
    private static LocalDateTime startOf(LocalDate day) {
        return day.atStartOfDay();
    }

    /** The last instant of a day, so a payment recorded at tea time on the last day counts. */
    private static LocalDateTime endOf(LocalDate day) {
        return day.atTime(LocalTime.MAX);
    }

    private static List<TitleCount> titles(List<ReportRepository.TitleCount> rows) {
        return rows.stream()
                .map(row -> new TitleCount(row.getTitle(), row.getAuthor(), row.getIssues()))
                .toList();
    }

    private static List<CategoryCount> categories(List<ReportRepository.CategoryCount> rows) {
        return rows.stream()
                .map(row -> new CategoryCount(row.getCategory(), row.getIssues()))
                .toList();
    }

    /**
     * Issues and returns, merged into one row per month.
     *
     * <p>Two queries rather than one, because a month with issues and no returns
     * and a month with returns and no issues both have to appear - an inner join
     * between the two would drop exactly the months worth noticing. Merged on an
     * ordered map so the result stays oldest first.</p>
     */
    private static List<MonthPoint> circulation(List<MonthCount> issues, List<MonthCount> returns) {
        Map<Integer, long[]> byMonth = new LinkedHashMap<>();

        issues.forEach(row -> byMonth
                .computeIfAbsent(key(row), month -> new long[2])[0] = row.getTotal());
        returns.forEach(row -> byMonth
                .computeIfAbsent(key(row), month -> new long[2])[1] = row.getTotal());

        List<MonthPoint> points = new ArrayList<>(byMonth.size());
        byMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> points.add(new MonthPoint(
                        entry.getKey() / 100,
                        entry.getKey() % 100,
                        entry.getValue()[0],
                        entry.getValue()[1])));

        return points;
    }

    private static List<MonthMoney> overdue(List<MonthCount> rows) {
        return rows.stream()
                .map(row -> new MonthMoney(row.getYear(), row.getMonth(), row.getTotal()))
                .toList();
    }

    /** Year and month as one sortable number, so 2026-03 orders after 2025-12. */
    private static int key(MonthCount row) {
        return row.getYear() * 100 + row.getMonth();
    }
}
