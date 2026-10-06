package com.library.lms.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.DashboardResponse;
import com.library.lms.dto.DashboardResponse.ActivityEntry;
import com.library.lms.dto.DashboardResponse.CatalogueCounts;
import com.library.lms.dto.DashboardResponse.CirculationCounts;
import com.library.lms.dto.DashboardResponse.MemberCounts;
import com.library.lms.dto.DashboardResponse.PeopleCounts;
import com.library.lms.dto.DashboardResponse.SystemCounts;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.RegistrationStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.DigitalResourceRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * What each account is shown when it opens the application.
 *
 * <p><b>The role decides what is computed, not what is filtered.</b> A member's
 * response is built without ever asking for a circulation figure, and an
 * administrator's without ever asking for a system-wide one. Nothing is
 * gathered and then removed, so there is no filtering step to get wrong and
 * nothing sitting in memory that the caller was not entitled to.
 *
 * <p><b>Every query is scoped to the caller's own library</b>, taken from their
 * account - the same rule the catalogue, the loans and the audit log already
 * follow. A member's own figures are scoped twice, by library and by user id,
 * so one member cannot be shown another's borrowing.
 *
 * <p>Read-only throughout: counts and sums, no writes, and no way to reach one.
 *
 * <p>These are counts, not listings. A dashboard says how many and how much; the
 * screens behind it say which, each with its own authorization.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** A loan that is still out. Returned ones cannot be overdue. */
    private static final List<TransactionStatus> OUT =
            List.of(TransactionStatus.ISSUED, TransactionStatus.OVERDUE);

    /** How many audit lines a dashboard shows. Enough to see activity, not a log viewer. */
    private static final int RECENT_ACTIVITY = 6;

    private final UserRepository userRepository;

    private final BookRepository bookRepository;

    private final CategoryRepository categoryRepository;

    private final DigitalResourceRepository resourceRepository;

    private final TransactionRepository transactionRepository;

    private final AuditEventRepository auditEventRepository;

    private final LibraryRepository libraryRepository;

    public DashboardService(UserRepository userRepository, BookRepository bookRepository,
            CategoryRepository categoryRepository, DigitalResourceRepository resourceRepository,
            TransactionRepository transactionRepository, AuditEventRepository auditEventRepository,
            LibraryRepository libraryRepository) {
        this.userRepository = userRepository;
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
        this.resourceRepository = resourceRepository;
        this.transactionRepository = transactionRepository;
        this.auditEventRepository = auditEventRepository;
        this.libraryRepository = libraryRepository;
    }

    /**
     * The dashboard for whoever is asking.
     *
     * @param authenticatedUsername the caller, from the filter chain - never from a request body
     */
    public DashboardResponse forUser(String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        Long libraryId = caller.getLibrary() == null ? null : caller.getLibrary().getId();
        Role role = caller.getRole();

        boolean staff = role == Role.ROLE_LIBRARIAN || role == Role.ROLE_ADMIN || role == Role.ROLE_SUPER_ADMIN;
        boolean administers = role == Role.ROLE_ADMIN || role == Role.ROLE_SUPER_ADMIN;

        return new DashboardResponse(
                role,
                caller.getLibrary() == null ? null : caller.getLibrary().getName(),
                libraryId == null ? null : catalogue(libraryId, role),
                role == Role.ROLE_MEMBER && libraryId != null ? member(libraryId, caller.getId()) : null,
                staff && libraryId != null ? circulation(libraryId) : null,
                administers && libraryId != null ? people(libraryId) : null,
                role == Role.ROLE_SUPER_ADMIN ? system() : null,
                staff && libraryId != null ? recentActivity(libraryId) : List.of());
    }

    /**
     * What the library holds.
     *
     * <p>A member is told how many resources they can open; staff are told how
     * many exist, because they can see the ones that are turned off. The same
     * distinction the resource listing already makes.</p>
     */
    private CatalogueCounts catalogue(Long libraryId, Role role) {
        long resources = role == Role.ROLE_MEMBER
                ? resourceRepository.countByLibraryIdAndEnabledTrue(libraryId)
                : resourceRepository.countByLibraryId(libraryId);

        return new CatalogueCounts(
                bookRepository.countByLibraryId(libraryId),
                categoryRepository.countByLibraryId(libraryId),
                resources);
    }

    /** The caller's own borrowing, scoped by library and by their own id. */
    private MemberCounts member(Long libraryId, Long userId) {
        return new MemberCounts(
                transactionRepository.countByUserIdAndStatusAndLibraryId(
                        userId, TransactionStatus.ISSUED, libraryId),
                transactionRepository.countByUserIdAndStatusInAndDueDateBeforeAndLibraryId(
                        userId, OUT, LocalDate.now(), libraryId),
                transactionRepository.countByUserIdAndFinePaymentStatusAndLibraryId(
                        userId, FinePaymentStatus.UNPAID, libraryId),
                transactionRepository.sumFinesForUser(libraryId, userId, FinePaymentStatus.UNPAID));
    }

    /** The library's borrowing, for the people who run it. */
    private CirculationCounts circulation(Long libraryId) {
        return new CirculationCounts(
                transactionRepository.countByStatusAndLibraryId(TransactionStatus.ISSUED, libraryId),
                transactionRepository.countByStatusInAndDueDateBeforeAndLibraryId(
                        OUT, LocalDate.now(), libraryId),
                transactionRepository.countByFinePaymentStatusAndLibraryId(FinePaymentStatus.UNPAID, libraryId),
                transactionRepository.sumFines(libraryId, FinePaymentStatus.UNPAID));
    }

    /** Who belongs to the library, and what is waiting on a decision. */
    private PeopleCounts people(Long libraryId) {
        return new PeopleCounts(
                userRepository.countByLibraryIdAndRole(libraryId, Role.ROLE_MEMBER),
                userRepository.countByLibraryIdAndRole(libraryId, Role.ROLE_LIBRARIAN),
                userRepository.countByLibraryIdAndRole(libraryId, Role.ROLE_ADMIN),
                userRepository.countByLibraryIdAndRoleAndRegistrationStatus(
                        libraryId, Role.ROLE_LIBRARIAN, RegistrationStatus.PENDING));
    }

    /**
     * Counts across every library.
     *
     * <p>The only unscoped query in this class, and only a super administrator
     * reaches it - the one role whose authority is system-wide.</p>
     */
    private SystemCounts system() {
        return new SystemCounts(
                libraryRepository.count(),
                userRepository.count(),
                userRepository.countByRoleAndRegistrationStatus(Role.ROLE_ADMIN, RegistrationStatus.PENDING));
    }

    /**
     * The library's latest audit entries, as a summary.
     *
     * <p>What happened and when. No actor and no target: naming who did what to
     * whom belongs to the audit screen, which has its own authorization.</p>
     */
    private List<ActivityEntry> recentActivity(Long libraryId) {
        return auditEventRepository
                .findByLibraryId(libraryId, PageRequest.of(0, RECENT_ACTIVITY,
                        Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id"))))
                .getContent()
                .stream()
                .map(event -> new ActivityEntry(event.getAction().name(), event.getOccurredAt()))
                .toList();
    }
}
