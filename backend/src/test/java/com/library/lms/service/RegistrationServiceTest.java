package com.library.lms.service;

import org.springframework.context.ApplicationEventPublisher;
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

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.dto.RegistrationRequest;
import com.library.lms.dto.RegistrationResponse;
import com.library.lms.dto.RegistrationType;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.Library;
import com.library.lms.entity.RegistrationStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.exception.DuplicateLibraryException;
import com.library.lms.exception.InvalidRegistrationException;
import com.library.lms.exception.LibraryNotJoinableException;
import com.library.lms.exception.RegistrationNotPendingException;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Who gets what by registering, and who may decide.
 *
 * <p>The assertions that matter most are about what a request cannot do: no
 * input produces a super administrator, no input produces a role at all, and no
 * administrator decides an application outside their own library.
 */
class RegistrationServiceTest {

    private static final long LIBRARY_ID = 7L;

    private static final long OTHER_LIBRARY_ID = 9L;

    /**
     * Where notification events go.
     *
     * <p>Mocked because these tests are about the business rule, not about
     * who is told. Passing a mock also demonstrates the point: this service
     * only ever publishes, and never sends anything itself.</p>
     */
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final UserRepository userRepository = mock(UserRepository.class);

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);

    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    private final AuditService auditService = mock(AuditService.class);

    private final RegistrationService service =
            new RegistrationService(userRepository, libraryRepository, passwordEncoder, auditService,
                    events);

    private Library library;

    private static Library library(long id, String name) {
        Library library = new Library();
        library.setId(id);
        library.setName(name);
        return library;
    }

    private static User account(long id, String username, Role role, Library library, RegistrationStatus status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setFullName("A Person");
        user.setRole(role);
        user.setLibrary(library);
        user.setRegistrationStatus(status);
        user.setEnabled(status == RegistrationStatus.APPROVED);
        return user;
    }

    @BeforeEach
    void stub() {
        library = library(LIBRARY_ID, "Central Library");

        when(passwordEncoder.encode(anyString())).thenReturn("encoded-not-a-real-hash");
        when(userRepository.existsByUsername(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(libraryRepository.findById(LIBRARY_ID)).thenReturn(Optional.of(library));
        when(libraryRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(libraryRepository.save(any(Library.class))).thenAnswer(call -> {
            Library saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(42L);
            }
            return saved;
        });
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(100L);
            }
            return saved;
        });
        // Every existing library is open unless a test says otherwise.
        when(userRepository.existsByLibraryIdAndRoleInAndRegistrationStatus(anyLong(), any(), any()))
                .thenReturn(true);
    }

    private static RegistrationRequest request(RegistrationType type) {
        RegistrationRequest request = new RegistrationRequest();
        request.setType(type);
        request.setUsername("asha");
        request.setEmail("asha@example.invalid");
        request.setFullName("Asha Rao");
        request.setPassword("a-long-enough-password");
        request.setLibraryId(LIBRARY_ID);
        request.setLibraryName("New Library");
        return request;
    }

    private User captureSaved() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        return saved.getValue();
    }

    // ---------- the role is never taken from the request ----------

    @ParameterizedTest
    @EnumSource(RegistrationType.class)
    void noRegistrationTypeEverProducesASuperAdministrator(RegistrationType type) {
        service.register(request(type));

        assertThat(captureSaved().getRole())
                .as("no input may produce the system-level role")
                .isNotEqualTo(Role.ROLE_SUPER_ADMIN);
    }

    @Test
    void theRequestHasNoFieldThatNamesARole() {
        // The escalation this prevents is not a check that could be forgotten:
        // there is no role-shaped field on the request to populate.
        List<Class<?>> fieldTypes = java.util.Arrays.stream(RegistrationRequest.class.getDeclaredFields())
                .<Class<?>>map(java.lang.reflect.Field::getType)
                .toList();

        assertThat(fieldTypes)
                .as("a role on the request would be an authority the caller chose")
                .doesNotContain(Role.class);
    }

    @Test
    void theRegistrationVocabularyHasNoPrivilegedSystemValue() {
        assertThat(java.util.Arrays.stream(RegistrationType.values()).map(Enum::name))
                .containsExactlyInAnyOrder("MEMBER", "LIBRARIAN", "ADMIN")
                .doesNotContain("SUPER_ADMIN");
    }

    @Test
    void aMemberRegistrationProducesAMember() {
        service.register(request(RegistrationType.MEMBER));

        User saved = captureSaved();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_MEMBER);
        assertThat(saved.getRegistrationStatus()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(saved.isEnabled()).as("nothing privileged, so nothing to review").isTrue();
        assertThat(saved.getLibrary().getId()).isEqualTo(LIBRARY_ID);
        assertThat(saved.getFullName()).isEqualTo("Asha Rao");
    }

    @Test
    void aLibrarianRegistrationIsAnApplicationAndCannotSignIn() {
        RegistrationResponse response = service.register(request(RegistrationType.LIBRARIAN));

        User saved = captureSaved();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_LIBRARIAN);
        assertThat(saved.getRegistrationStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(saved.isEnabled()).as("disabled is what refuses authentication").isFalse();
        assertThat(response.status()).isEqualTo(RegistrationStatus.PENDING);
    }

    @Test
    void anAdministratorRegistrationCreatesALibraryAndWaits() {
        service.register(request(RegistrationType.ADMIN));

        User saved = captureSaved();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(saved.getRegistrationStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(saved.isEnabled()).isFalse();
        verify(libraryRepository).save(any(Library.class));
    }

    @Test
    void theStoredPasswordIsEncodedAndNeverTheOneSent() {
        service.register(request(RegistrationType.MEMBER));

        assertThat(captureSaved().getPassword())
                .isEqualTo("encoded-not-a-real-hash")
                .isNotEqualTo("a-long-enough-password");
        verify(passwordEncoder).encode("a-long-enough-password");
    }

    // ---------- what is refused ----------

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "LIBRARIAN"})
    void joiningWithoutChoosingALibraryIsRefused(String type) {
        RegistrationRequest request = request(RegistrationType.valueOf(type));
        request.setLibraryId(null);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(InvalidRegistrationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void joiningALibraryThatDoesNotExistIsRefused() {
        RegistrationRequest request = request(RegistrationType.MEMBER);
        request.setLibraryId(404L);
        when(libraryRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(LibraryNotJoinableException.class);
    }

    @Test
    void joiningALibraryWhoseOwnAdministratorIsStillWaitingIsRefusedTheSameWay() {
        when(userRepository.existsByLibraryIdAndRoleInAndRegistrationStatus(eq(LIBRARY_ID), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.register(request(RegistrationType.MEMBER)))
                .as("the same answer as a library that does not exist, so nothing is confirmed")
                .isInstanceOf(LibraryNotJoinableException.class);
    }

    @Test
    void openingALibraryWithNoNameIsRefused() {
        RegistrationRequest request = request(RegistrationType.ADMIN);
        request.setLibraryName("  ");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(InvalidRegistrationException.class);
    }

    @Test
    void openingALibraryThatAlreadyExistsIsRefused() {
        when(libraryRepository.findByName("New Library")).thenReturn(Optional.of(library(1L, "New Library")));

        assertThatThrownBy(() -> service.register(request(RegistrationType.ADMIN)))
                .isInstanceOf(DuplicateLibraryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "email"})
    void aTakenUsernameOrEmailIsRefusedWithOneMessage(String taken) {
        when(userRepository.existsByUsername(anyString())).thenReturn("username".equals(taken));
        when(userRepository.existsByEmail(anyString())).thenReturn("email".equals(taken));

        assertThatThrownBy(() -> service.register(request(RegistrationType.MEMBER)))
                .as("one message for both, so registering cannot enumerate accounts")
                .isInstanceOf(DuplicateAccountException.class)
                .hasMessageContaining("username or email");
    }

    // ---------- audit ----------

    @Test
    void registeringIsAudited() {
        service.register(request(RegistrationType.MEMBER));

        verify(auditService).recordSuccess(eq(AuditAction.USER_REGISTERED), eq(LIBRARY_ID), eq(null), any());
    }

    @Test
    void applyingForALibraryIsAuditedAsWell() {
        service.register(request(RegistrationType.ADMIN));

        verify(auditService).recordSuccess(eq(AuditAction.USER_REGISTERED), anyLong(), eq(null), any());
        verify(auditService).recordSuccess(eq(AuditAction.LIBRARY_APPLIED), anyLong(), eq(null), any());
    }

    // ---------- deciding ----------

    private void deciderIs(User decider) {
        when(userRepository.findByUsername(decider.getUsername())).thenReturn(Optional.of(decider));
    }

    private User applicantIs(User applicant) {
        when(userRepository.findById(applicant.getId())).thenReturn(Optional.of(applicant));
        return applicant;
    }

    @Test
    void anAdministratorApprovesALibrarianOfTheirOwnLibrary() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        User applicant = applicantIs(
                account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING));

        service.approve(2L, "admin");

        assertThat(applicant.getRegistrationStatus()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(applicant.isEnabled()).as("approval is what turns the account on").isTrue();
        verify(auditService).recordSuccess(eq(AuditAction.REGISTRATION_APPROVED), eq(LIBRARY_ID), eq(1L), any());
    }

    @Test
    void aRejectedApplicantStaysDisabledAndTheRowIsKept() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        User applicant = applicantIs(
                account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING));

        service.reject(2L, "admin");

        assertThat(applicant.getRegistrationStatus()).isEqualTo(RegistrationStatus.REJECTED);
        assertThat(applicant.isEnabled()).isFalse();
        verify(auditService).recordSuccess(eq(AuditAction.REGISTRATION_REJECTED), eq(LIBRARY_ID), eq(1L), any());
    }

    @Test
    void anAdministratorCannotDecideAnotherLibrarysApplication() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        User applicant = applicantIs(account(2L, "elsewhere", Role.ROLE_LIBRARIAN,
                library(OTHER_LIBRARY_ID, "Other Library"), RegistrationStatus.PENDING));

        assertThatThrownBy(() -> service.approve(2L, "admin"))
                .as("library isolation holds for approvals too")
                .isInstanceOf(RegistrationNotPendingException.class);

        assertThat(applicant.getRegistrationStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(applicant.isEnabled()).isFalse();
    }

    @Test
    void anAdministratorCannotApproveAnAdministratorApplication() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        User applicant = applicantIs(
                account(2L, "wants-a-library", Role.ROLE_ADMIN, library, RegistrationStatus.PENDING));

        assertThatThrownBy(() -> service.approve(2L, "admin"))
                .as("only a super administrator opens a library")
                .isInstanceOf(RegistrationNotPendingException.class);

        assertThat(applicant.isEnabled()).isFalse();
    }

    @Test
    void aSuperAdministratorApprovesAnAdministratorApplicationInAnyLibrary() {
        deciderIs(account(1L, "root", Role.ROLE_SUPER_ADMIN,
                library(OTHER_LIBRARY_ID, "Somewhere Else"), RegistrationStatus.APPROVED));
        User applicant = applicantIs(
                account(2L, "wants-a-library", Role.ROLE_ADMIN, library, RegistrationStatus.PENDING));

        service.approve(2L, "root");

        assertThat(applicant.getRegistrationStatus()).isEqualTo(RegistrationStatus.APPROVED);
        assertThat(applicant.isEnabled()).isTrue();
    }

    @Test
    void aSuperAdministratorDoesNotDecideLibrarianApplications() {
        deciderIs(account(1L, "root", Role.ROLE_SUPER_ADMIN, library, RegistrationStatus.APPROVED));
        applicantIs(account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING));

        assertThatThrownBy(() -> service.approve(2L, "root"))
                .as("that is the library administrator's decision, not a system one")
                .isInstanceOf(RegistrationNotPendingException.class);
    }

    @Test
    void anAlreadySettledRegistrationCannotBeDecidedAgain() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        applicantIs(account(2L, "already", Role.ROLE_LIBRARIAN, library, RegistrationStatus.APPROVED));

        assertThatThrownBy(() -> service.approve(2L, "admin"))
                .isInstanceOf(RegistrationNotPendingException.class);
    }

    @Test
    void aLibrarianCannotDecideAnything() {
        deciderIs(account(1L, "librarian", Role.ROLE_LIBRARIAN, library, RegistrationStatus.APPROVED));
        applicantIs(account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING));

        assertThatThrownBy(() -> service.approve(2L, "librarian"))
                .isInstanceOf(RegistrationNotPendingException.class);
    }

    @Test
    void anAdministratorOnlyListsTheirOwnLibrarysLibrarianApplications() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        when(userRepository.findByRegistrationStatusAndRoleAndLibraryId(
                RegistrationStatus.PENDING, Role.ROLE_LIBRARIAN, LIBRARY_ID))
                .thenReturn(List.of(account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING)));

        assertThat(service.pending("admin")).hasSize(1);

        verify(userRepository).findByRegistrationStatusAndRoleAndLibraryId(
                RegistrationStatus.PENDING, Role.ROLE_LIBRARIAN, LIBRARY_ID);
        verify(userRepository, never()).findByRegistrationStatusAndRole(any(), any());
    }

    @Test
    void aSuperAdministratorListsAdministratorApplicationsAcrossLibraries() {
        deciderIs(account(1L, "root", Role.ROLE_SUPER_ADMIN, library, RegistrationStatus.APPROVED));
        when(userRepository.findByRegistrationStatusAndRole(RegistrationStatus.PENDING, Role.ROLE_ADMIN))
                .thenReturn(List.of(account(2L, "wants", Role.ROLE_ADMIN, library, RegistrationStatus.PENDING)));

        assertThat(service.pending("root")).hasSize(1);

        verify(userRepository, never()).findByRegistrationStatusAndRoleAndLibraryId(any(), any(), anyLong());
    }

    @Test
    void aPendingListingCarriesNoCredential() {
        deciderIs(account(1L, "admin", Role.ROLE_ADMIN, library, RegistrationStatus.APPROVED));
        when(userRepository.findByRegistrationStatusAndRoleAndLibraryId(any(), any(), anyLong()))
                .thenReturn(List.of(account(2L, "wanted", Role.ROLE_LIBRARIAN, library, RegistrationStatus.PENDING)));

        assertThat(java.util.Arrays.stream(
                com.library.lms.dto.PendingRegistrationResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("password", "passwordHash", "token");

        assertThat(service.pending("admin").get(0).username()).isEqualTo("wanted");
    }
}
