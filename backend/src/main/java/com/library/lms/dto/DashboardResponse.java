package com.library.lms.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.library.lms.entity.Role;

/**
 * What one account sees when it opens the application.
 *
 * <p><b>Every section is computed for the caller, or is null.</b> A member's
 * response carries no circulation figures - not hidden, not zeroed, but absent,
 * because the service never asks for them. An administrator's carries no
 * system-wide counts unless they are a super administrator. So the shape of the
 * response is itself the authorization: there is nothing to filter on the way
 * out and nothing for a client to reveal by asking differently.
 *
 * <p><b>Every number is real.</b> Each one is a count or a sum the database
 * answered, scoped to the caller's own library. Nothing here is estimated,
 * rounded up, or invented to make a panel look populated - a library with an
 * empty shelf reports zero.
 *
 * @param role         the caller's role, so the client renders the right panels
 * @param libraryName  the caller's own library; null only for an account with none
 * @param catalogue    what the library holds - everybody sees this
 * @param member       the caller's own loans and fines; null unless they are a member
 * @param circulation  the library's loans and fines; null unless they are staff
 * @param people       who belongs to the library; null unless they administer it
 * @param system       counts across every library; null unless super administrator
 * @param recentActivity the library's latest audit entries; empty unless staff
 */
public record DashboardResponse(
        Role role,
        String libraryName,
        CatalogueCounts catalogue,
        MemberCounts member,
        CirculationCounts circulation,
        PeopleCounts people,
        SystemCounts system,
        List<ActivityEntry> recentActivity) {

    /** What the library holds. Visible to everybody who belongs to it. */
    public record CatalogueCounts(long titles, long categories, long digitalResources) {
    }

    /**
     * The caller's own borrowing.
     *
     * @param amountOwed the sum of this member's unsettled fines, never another's
     */
    public record MemberCounts(long currentLoans, long overdueLoans, long unpaidFines, double amountOwed) {
    }

    /**
     * The library's borrowing, for the people who run it.
     *
     * @param finesOutstanding what the library is owed, across its members
     */
    public record CirculationCounts(long activeLoans, long overdueLoans, long unpaidFines,
            double finesOutstanding) {
    }

    /** Who belongs to the library, for whoever administers it. */
    public record PeopleCounts(long members, long librarians, long administrators,
            long pendingRegistrations) {
    }

    /**
     * Counts across every library.
     *
     * @param pendingLibraryApplications administrator applications, which would open a library
     */
    public record SystemCounts(long libraries, long accounts, long pendingLibraryApplications) {
    }

    /**
     * One line of the audit trail, as a dashboard shows it.
     *
     * <p>The action and when it happened, and nothing else. No actor, no target
     * and no ids: a summary panel needs to say what has been going on, and
     * naming who did what to whom is the audit screen's job, behind its own
     * authorization.</p>
     */
    public record ActivityEntry(String action, LocalDateTime occurredAt) {
    }
}
