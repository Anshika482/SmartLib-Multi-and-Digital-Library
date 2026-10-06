package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.library.lms.dto.DashboardResponse;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
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
 * What each role is shown, and - more to the point - what it is not.
 *
 * <p>The assertions that matter are about absence and about scope: a member's
 * response carries no circulation figures because none were ever asked for, and
 * every query that runs carries the caller's own library id.
 */
class DashboardServiceTest {

    private static final long LIBRARY_ID = 7L;

    private static final long USER_ID = 21L;

    private final UserRepository userRepository = mock(UserRepository.class);
    private final BookRepository bookRepository = mock(BookRepository.class);
    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final DigitalResourceRepository resourceRepository = mock(DigitalResourceRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);

    private final DashboardService service = new DashboardService(userRepository, bookRepository,
            categoryRepository, resourceRepository, transactionRepository, auditEventRepository,
            libraryRepository);

    private Library library;

    private User signedInAs(Role role) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("caller");
        user.setRole(role);
        user.setLibrary(library);
        when(userRepository.findByUsername("caller")).thenReturn(Optional.of(user));
        return user;
    }

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Central Library");

        when(bookRepository.countByLibraryId(anyLong())).thenReturn(25L);
        when(categoryRepository.countByLibraryId(anyLong())).thenReturn(13L);
        when(resourceRepository.countByLibraryId(anyLong())).thenReturn(9L);
        when(resourceRepository.countByLibraryIdAndEnabledTrue(anyLong())).thenReturn(7L);

        when(transactionRepository.countByStatusAndLibraryId(any(), anyLong())).thenReturn(4L);
        when(transactionRepository.countByStatusInAndDueDateBeforeAndLibraryId(any(), any(), anyLong()))
                .thenReturn(2L);
        when(transactionRepository.countByFinePaymentStatusAndLibraryId(any(), anyLong())).thenReturn(3L);
        when(transactionRepository.sumFines(anyLong(), any())).thenReturn(12.5);

        when(transactionRepository.countByUserIdAndStatusAndLibraryId(anyLong(), any(), anyLong()))
                .thenReturn(1L);
        when(transactionRepository.countByUserIdAndStatusInAndDueDateBeforeAndLibraryId(
                anyLong(), any(), any(), anyLong())).thenReturn(1L);
        when(transactionRepository.countByUserIdAndFinePaymentStatusAndLibraryId(anyLong(), any(), anyLong()))
                .thenReturn(1L);
        when(transactionRepository.sumFinesForUser(anyLong(), anyLong(), any())).thenReturn(3.0);

        when(userRepository.countByLibraryIdAndRole(anyLong(), any())).thenReturn(5L);
        when(userRepository.countByLibraryIdAndRoleAndRegistrationStatus(anyLong(), any(), any()))
                .thenReturn(2L);
        when(userRepository.countByRoleAndRegistrationStatus(any(), any())).thenReturn(1L);
        when(userRepository.count()).thenReturn(40L);
        when(libraryRepository.count()).thenReturn(3L);

        when(auditEventRepository.findByLibraryId(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    // ---------- what each role gets ----------

    @Test
    void aMemberGetsTheirOwnBorrowingAndNoLibraryWideFigures() {
        signedInAs(Role.ROLE_MEMBER);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.member()).isNotNull();
        assertThat(dashboard.member().currentLoans()).isEqualTo(1);
        assertThat(dashboard.member().amountOwed()).isEqualTo(3.0);

        assertThat(dashboard.circulation()).as("a member is not shown the library's loans").isNull();
        assertThat(dashboard.people()).as("nor who belongs to it").isNull();
        assertThat(dashboard.system()).as("nor anything system-wide").isNull();
        assertThat(dashboard.recentActivity()).as("nor the audit trail").isEmpty();
    }

    @Test
    void aMembersLibraryWideFiguresAreNeverEvenQueried() {
        signedInAs(Role.ROLE_MEMBER);

        service.forUser("caller");

        // Not gathered and filtered out - never asked for. There is no
        // filtering step here to get wrong later.
        verify(transactionRepository, never()).countByStatusAndLibraryId(any(), anyLong());
        verify(transactionRepository, never()).sumFines(anyLong(), any());
        verify(userRepository, never()).countByLibraryIdAndRole(anyLong(), any());
        verify(libraryRepository, never()).count();
        verify(auditEventRepository, never()).findByLibraryId(anyLong(), any(Pageable.class));
    }

    @Test
    void aLibrarianGetsCirculationButNotThePeopleOrTheSystem() {
        signedInAs(Role.ROLE_LIBRARIAN);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.circulation()).isNotNull();
        assertThat(dashboard.circulation().activeLoans()).isEqualTo(4);
        assertThat(dashboard.circulation().finesOutstanding()).isEqualTo(12.5);

        assertThat(dashboard.member()).as("staff have no personal borrowing panel").isNull();
        assertThat(dashboard.people()).isNull();
        assertThat(dashboard.system()).isNull();
    }

    @Test
    void anAdministratorGetsThePeopleAsWellButNotTheSystem() {
        signedInAs(Role.ROLE_ADMIN);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.circulation()).isNotNull();
        assertThat(dashboard.people()).isNotNull();
        assertThat(dashboard.people().members()).isEqualTo(5);
        assertThat(dashboard.people().pendingRegistrations()).isEqualTo(2);

        assertThat(dashboard.system()).as("one library's administrator is not the system's").isNull();
    }

    @Test
    void aSuperAdministratorGetsTheSystemCounts() {
        signedInAs(Role.ROLE_SUPER_ADMIN);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.system()).isNotNull();
        assertThat(dashboard.system().libraries()).isEqualTo(3);
        assertThat(dashboard.system().accounts()).isEqualTo(40);
        assertThat(dashboard.system().pendingLibraryApplications()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everybodySeesTheCatalogueAndTheirOwnLibraryName(Role role) {
        signedInAs(role);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.catalogue()).isNotNull();
        assertThat(dashboard.catalogue().titles()).isEqualTo(25);
        assertThat(dashboard.libraryName()).isEqualTo("Central Library");
        assertThat(dashboard.role()).isEqualTo(role);
    }

    @Test
    void aMemberIsCountedOnlyTheResourcesTheyCanOpen() {
        signedInAs(Role.ROLE_MEMBER);

        assertThat(service.forUser("caller").catalogue().digitalResources())
                .as("the enabled ones, as their resource listing already shows")
                .isEqualTo(7);
    }

    @Test
    void staffAreCountedEveryResourceIncludingTheOnesTurnedOff() {
        signedInAs(Role.ROLE_LIBRARIAN);

        assertThat(service.forUser("caller").catalogue().digitalResources()).isEqualTo(9);
    }

    // ---------- library isolation ----------

    @Test
    void everyQueryCarriesTheCallersOwnLibraryId() {
        signedInAs(Role.ROLE_ADMIN);

        service.forUser("caller");

        ArgumentCaptor<Long> books = ArgumentCaptor.forClass(Long.class);
        verify(bookRepository).countByLibraryId(books.capture());
        assertThat(books.getValue()).isEqualTo(LIBRARY_ID);

        verify(categoryRepository).countByLibraryId(LIBRARY_ID);
        verify(transactionRepository).countByStatusAndLibraryId(any(), eq(LIBRARY_ID));
        verify(transactionRepository).sumFines(eq(LIBRARY_ID), any());
        verify(userRepository).countByLibraryIdAndRole(eq(LIBRARY_ID), eq(Role.ROLE_MEMBER));
        verify(auditEventRepository).findByLibraryId(eq(LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void aMembersOwnFiguresAreScopedByLibraryAndByTheirOwnId() {
        signedInAs(Role.ROLE_MEMBER);

        service.forUser("caller");

        // Scoped twice: one member must not be counted another's borrowing even
        // within the same library.
        verify(transactionRepository).countByUserIdAndStatusAndLibraryId(
                USER_ID, TransactionStatus.ISSUED, LIBRARY_ID);
        verify(transactionRepository).sumFinesForUser(LIBRARY_ID, USER_ID, FinePaymentStatus.UNPAID);
    }

    @Test
    void overdueIsCountedFromLoansStillOutAndPastDue() {
        signedInAs(Role.ROLE_LIBRARIAN);

        service.forUser("caller");

        ArgumentCaptor<java.util.Collection<TransactionStatus>> statuses =
                ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<LocalDate> due = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactionRepository)
                .countByStatusInAndDueDateBeforeAndLibraryId(statuses.capture(), due.capture(), eq(LIBRARY_ID));

        assertThat(statuses.getValue())
                .as("a returned loan cannot be overdue")
                .containsExactlyInAnyOrder(TransactionStatus.ISSUED, TransactionStatus.OVERDUE)
                .doesNotContain(TransactionStatus.RETURNED);
        assertThat(due.getValue()).isEqualTo(LocalDate.now());
    }

    @Test
    void pendingRegistrationsCountedForAnAdministratorAreTheirOwnLibrarysLibrarians() {
        signedInAs(Role.ROLE_ADMIN);

        service.forUser("caller");

        verify(userRepository).countByLibraryIdAndRoleAndRegistrationStatus(
                LIBRARY_ID, Role.ROLE_LIBRARIAN, RegistrationStatus.PENDING);
    }

    @Test
    void pendingApplicationsCountedForASuperAdministratorAreAdministrators() {
        signedInAs(Role.ROLE_SUPER_ADMIN);

        service.forUser("caller");

        verify(userRepository).countByRoleAndRegistrationStatus(
                Role.ROLE_ADMIN, RegistrationStatus.PENDING);
    }

    // ---------- activity ----------

    @Test
    void recentActivityCarriesWhatHappenedAndWhenAndNothingElse() {
        signedInAs(Role.ROLE_ADMIN);

        // Immutable: built through its own constructor, actor and target
        // included, so the test proves those are dropped rather than absent.
        AuditEvent event = new AuditEvent(library, 99L, AuditAction.BOOK_ISSUED,
                AuditTargetType.LOAN, 123L, AuditOutcome.SUCCESS,
                LocalDateTime.of(2026, 9, 24, 10, 0));
        when(auditEventRepository.findByLibraryId(anyLong(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        List<DashboardResponse.ActivityEntry> activity = service.forUser("caller").recentActivity();

        assertThat(activity).hasSize(1);
        assertThat(activity.get(0).action()).isEqualTo("BOOK_ISSUED");
        assertThat(activity.get(0).occurredAt()).isEqualTo(LocalDateTime.of(2026, 9, 24, 10, 0));

        // Who did it and to whom belong to the audit screen, behind its own
        // authorization. A summary panel does not name people.
        assertThat(java.util.Arrays.stream(
                DashboardResponse.ActivityEntry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrder("action", "occurredAt");
    }

    @Test
    void aLibraryWithNothingInItReportsZeroRatherThanAnythingInvented() {
        signedInAs(Role.ROLE_LIBRARIAN);
        when(bookRepository.countByLibraryId(anyLong())).thenReturn(0L);
        when(categoryRepository.countByLibraryId(anyLong())).thenReturn(0L);
        when(resourceRepository.countByLibraryId(anyLong())).thenReturn(0L);
        when(transactionRepository.countByStatusAndLibraryId(any(), anyLong())).thenReturn(0L);
        when(transactionRepository.sumFines(anyLong(), any())).thenReturn(0.0);

        DashboardResponse dashboard = service.forUser("caller");

        assertThat(dashboard.catalogue().titles()).isZero();
        assertThat(dashboard.circulation().activeLoans()).isZero();
        assertThat(dashboard.circulation().finesOutstanding()).isZero();
    }

    // ---------- the caller ----------

    @Test
    void anUnknownCallerIsRefusedBeforeAnythingIsCounted() {
        assertThatThrownBy(() -> service.forUser("ghost"))
                .isInstanceOf(UserNotFoundException.class);

        verify(bookRepository, never()).countByLibraryId(anyLong());
    }

    @Test
    void theServiceOnlyEverReads() {
        assertThat(java.util.Arrays.stream(DashboardService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .as("counts and sums, with no way to write anything")
                .containsExactly("forUser");
    }

    @Test
    void theCallerIsTakenFromTheNameGivenAndNothingElse() {
        signedInAs(Role.ROLE_MEMBER);

        service.forUser("caller");

        verify(userRepository).findByUsername("caller");
        verify(userRepository, never()).findById(anyLong());
        verify(userRepository, never()).findByEmail(anyString());
    }
}
