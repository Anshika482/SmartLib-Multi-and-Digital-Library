package com.library.lms.dto;

import java.time.LocalDate;
import java.util.List;

import com.library.lms.entity.Role;

/**
 * One report, for one caller, over one range of dates.
 *
 * <p><b>Every figure was counted by the database.</b> Nothing here is a sample
 * or a projection from partial data, and nothing is left for a client to add
 * up - a total worked out in a browser from one page of rows would be wrong the
 * moment there were two pages.
 *
 * <p><b>What the scope means.</b> {@code systemWide} is true only for a super
 * administrator, and then {@code libraryName} is null because the report is not
 * about one library. For everybody else it is that caller's own library and
 * nothing else exists in these numbers.
 *
 * <p><b>Nobody's name is in here.</b> The breakdowns are by book, by shelf and
 * by month; who borrowed what is not a report, it is a loan record, and it has
 * its own screen with its own authorization.
 *
 * @param scope        the role the report was built for
 * @param systemWide   whether it spans every library
 * @param libraryName  the library it describes, or null when it spans all of them
 * @param from         the first day counted, inclusive
 * @param to           the last day counted, inclusive
 * @param totals       the headline figures
 * @param mostIssued   the titles borrowed most in the range
 * @param categories   the shelves borrowed from most in the range
 * @param circulation  issues and returns per calendar month
 * @param overdueTrend loans that fell overdue per month
 */
public record ReportResponse(
        Role scope,
        boolean systemWide,
        String libraryName,
        LocalDate from,
        LocalDate to,
        Totals totals,
        List<TitleCount> mostIssued,
        List<CategoryCount> categories,
        List<MonthPoint> circulation,
        List<MonthMoney> overdueTrend) {

    /**
     * The headline figures.
     *
     * <p>Some are about the range and some are about now, and the distinction is
     * deliberate rather than an oversight. Issues, returns, fines raised and
     * fines paid happened <i>within</i> the dates. Books, members, active loans
     * and outstanding fines are facts about the library <i>today</i> - asking
     * how many books a library owned during a week last March is a different
     * question, and answering it would need history this schema does not
     * keep.</p>
     *
     * @param totalBooks       titles held now
     * @param totalMembers     member accounts now
     * @param issues           copies issued within the range
     * @param returns          copies returned within the range
     * @param activeLoans      copies out now
     * @param overdueLoans     copies out now and past due
     * @param finesRaised      fines fixed on loans returned within the range
     * @param finesPaid        fines recorded as settled within the range
     * @param finesOutstanding everything still owed now
     * @param paymentsTaken    money taken through the gateway within the range
     */
    public record Totals(
            long totalBooks,
            long totalMembers,
            long issues,
            long returns,
            long activeLoans,
            long overdueLoans,
            double finesRaised,
            double finesPaid,
            double finesOutstanding,
            double paymentsTaken) {
    }

    /** One title and how often it went out. No id: a report names books, it does not link to rows. */
    public record TitleCount(String title, String author, long issues) {
    }

    /** One shelf and how often anything on it went out. */
    public record CategoryCount(String category, long issues) {
    }

    /** One month of circulation. */
    public record MonthPoint(int year, int month, long issues, long returns) {
    }

    /** One month of something measured in money or counts - used for the overdue trend. */
    public record MonthMoney(int year, int month, long count) {
    }
}
