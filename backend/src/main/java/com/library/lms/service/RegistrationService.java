package com.library.lms.service;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.PendingRegistrationResponse;
import com.library.lms.dto.RegistrationRequest;
import com.library.lms.dto.RegistrationResponse;
import com.library.lms.dto.RegistrationType;
import com.library.lms.entity.NotificationKind;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.Library;
import com.library.lms.entity.RegistrationStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.exception.InvalidRegistrationException;
import com.library.lms.exception.DuplicateLibraryException;
import com.library.lms.exception.LibraryNotJoinableException;
import com.library.lms.exception.RegistrationNotPendingException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Registering, and deciding on the registrations that need deciding.
 *
 * <p><b>The role is chosen here, from a table this class owns.</b> A request
 * names a {@link RegistrationType}, never a {@code Role}; the two are different
 * types and the mapping below is the only bridge between them. There is no
 * entry for a super administrator, so no request - however edited - can produce
 * one. This is the same shape as the assignable-roles guard in
 * {@code UserService}, which already refuses to let an administrator mint an
 * administrator.
 *
 * <p><b>What each kind produces:</b>
 * <ul>
 *   <li><b>Member</b> - joins an existing library, approved immediately, signs
 *       in at once. Nothing privileged is granted, so nothing has to be
 *       reviewed.</li>
 *   <li><b>Librarian</b> - applies to an existing library. Pending and disabled
 *       until an administrator <i>of that same library</i> decides. That is the
 *       isolation rule the rest of the system already keeps: staff of one
 *       library have no say over another.</li>
 *   <li><b>Administrator</b> - applies to open a new library. The library row
 *       and the account are created together, both waiting, and a super
 *       administrator decides. Until then the library is not usable and does
 *       not appear anywhere a member could pick it.</li>
 * </ul>
 *
 * <p><b>A pending account is saved disabled.</b> Authentication is refused by
 * the mechanism that already refuses a disabled account, with the same fixed
 * message a wrong password gets - so this class adds no new way to be rejected,
 * and no new way to tell accounts apart from outside.
 */
@Service
public class RegistrationService {

    /**
     * What each kind of registration is worth.
     *
     * <p>Fixed, and holding no privileged value. A type with no entry cannot be
     * registered at all.
     */
    private static final Map<RegistrationType, Role> ROLE_OF = new EnumMap<>(Map.of(
            RegistrationType.MEMBER, Role.ROLE_MEMBER,
            RegistrationType.LIBRARIAN, Role.ROLE_LIBRARIAN,
            RegistrationType.ADMIN, Role.ROLE_ADMIN));

    /** Which kinds need somebody to agree before the account works. */
    private static final Map<RegistrationType, RegistrationStatus> STATUS_OF = new EnumMap<>(Map.of(
            RegistrationType.MEMBER, RegistrationStatus.APPROVED,
            RegistrationType.LIBRARIAN, RegistrationStatus.PENDING,
            RegistrationType.ADMIN, RegistrationStatus.PENDING));

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;

    private final LibraryRepository libraryRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuditService auditService;


    /**
     * Where notification events go.
     *
     * <p>Published, never sent. What is delivered, to whom, and whether it has
     * already gone are {@code NotificationService}'s decisions - taken after
     * this service's transaction commits, so nothing here waits on a mail
     * server and no failure to send can undo what this service did.</p>
     */
    private final ApplicationEventPublisher events;

    public RegistrationService(UserRepository userRepository, LibraryRepository libraryRepository,
            PasswordEncoder passwordEncoder, AuditService auditService,
            ApplicationEventPublisher events) {
        this.events = events;
        this.userRepository = userRepository;
        this.libraryRepository = libraryRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- register

    /**
     * Registers somebody, or records their application.
     *
     * @throws InvalidRegistrationException if the request omits what its kind needs
     * @throws DuplicateAccountException    if the username or email is taken
     * @throws LibraryNotJoinableException  if the chosen library is not one that can be joined
     */
    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        RegistrationType type = request.getType();
        Role role = ROLE_OF.get(type);

        if (role == null) {
            // Unreachable through the API - validation rejects a null type and
            // Jackson rejects an unknown one - but a lookup that can miss must
            // not fall through to a default that grants something.
            throw new InvalidRegistrationException("That is not a kind of registration this system offers.");
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim();
        String fullName = request.getFullName().trim();

        // One check, one message, whichever of the two matched.
        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new DuplicateAccountException();
        }

        Library library = type == RegistrationType.ADMIN
                ? newLibraryFor(request)
                : joinableLibrary(request);

        RegistrationStatus status = STATUS_OF.get(type);

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFullName(fullName);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(role);
        user.setLibrary(library);
        user.setRegistrationStatus(status);

        // Disabled while pending, which is what stops authentication. Approval
        // is the only thing that turns it on.
        user.setEnabled(status == RegistrationStatus.APPROVED);

        User saved = userRepository.save(user);

        auditService.recordSuccess(AuditAction.USER_REGISTERED, library.getId(), null,
                AuditTarget.user(saved.getId()));

        if (type == RegistrationType.ADMIN) {
            auditService.recordSuccess(AuditAction.LIBRARY_APPLIED, library.getId(), null,
                    AuditTarget.library(library.getId()));
        }

        // By id and role. Never the username, the email or the name.
        log.info("Registration recorded: userId={} role={} libraryId={} status={}",
                saved.getId(), role, library.getId(), status);

        return new RegistrationResponse(status, messageFor(type, library));
    }

    /** An existing library a member or librarian may join. */
    private Library joinableLibrary(RegistrationRequest request) {
        if (request.getLibraryId() == null) {
            throw new InvalidRegistrationException("Choose the library you are joining.");
        }

        Library library = libraryRepository.findById(request.getLibraryId())
                .orElseThrow(LibraryNotJoinableException::new);

        // A library whose own administrator is still waiting is not open to
        // anybody else yet. Same answer as a library that does not exist, so a
        // stranger learns nothing about applications in flight.
        if (!hasApprovedAdministrator(library.getId())) {
            throw new LibraryNotJoinableException();
        }

        return library;
    }

    /** A new library, created alongside the application to administer it. */
    private Library newLibraryFor(RegistrationRequest request) {
        if (request.getLibraryName() == null || request.getLibraryName().isBlank()) {
            throw new InvalidRegistrationException("Give the name of the library you want to open.");
        }

        String name = request.getLibraryName().trim();

        if (libraryRepository.findByName(name).isPresent()) {
            // The same exception the authenticated create-library path raises,
            // so the two agree on what a clash is and what it is called.
            throw new DuplicateLibraryException();
        }

        Library library = new Library();
        library.setName(name);

        return libraryRepository.save(library);
    }

    /**
     * Whether a library has an administrator who has been approved.
     *
     * <p>This is what active means, and it is derived rather than stored. A
     * library opened by the bootstrap or by an administrator has one from the
     * start; one that exists only because somebody applied for it does not, and
     * so is invisible to everybody until that application is approved. No extra
     * column, and no way for the two to disagree.
     */
    @Transactional(readOnly = true)
    public boolean hasApprovedAdministrator(Long libraryId) {
        return userRepository.existsByLibraryIdAndRoleInAndRegistrationStatus(
                libraryId, List.of(Role.ROLE_ADMIN, Role.ROLE_SUPER_ADMIN), RegistrationStatus.APPROVED);
    }

    private static String messageFor(RegistrationType type, Library library) {
        return switch (type) {
            case MEMBER -> "Your account is ready. Sign in with your username and password.";
            case LIBRARIAN -> "Your application has been sent to the administrators of " + library.getName()
                    + ". You will be able to sign in once it is approved.";
            case ADMIN -> "Your application to open " + library.getName()
                    + " has been received. You will be able to sign in once it is approved.";
        };
    }

    // ---------------------------------------------------------------- decide

    /**
     * The registrations this caller is entitled to decide.
     *
     * <p>An administrator sees librarian applications to their own library. A
     * super administrator sees administrator applications, which are the ones
     * that would open a new library. Neither sees the other, and an
     * administrator never sees another library.
     */
    @Transactional(readOnly = true)
    public List<PendingRegistrationResponse> pending(String authenticatedUsername) {
        User decider = requireUser(authenticatedUsername);

        List<User> waiting = decider.getRole() == Role.ROLE_SUPER_ADMIN
                ? userRepository.findByRegistrationStatusAndRole(RegistrationStatus.PENDING, Role.ROLE_ADMIN)
                : userRepository.findByRegistrationStatusAndRoleAndLibraryId(
                        RegistrationStatus.PENDING, Role.ROLE_LIBRARIAN, decider.getLibrary().getId());

        return waiting.stream().map(RegistrationService::toPending).toList();
    }

    /** Approves a waiting registration, which is what makes the account usable. */
    @Transactional
    public PendingRegistrationResponse approve(Long userId, String authenticatedUsername) {
        return decide(userId, authenticatedUsername, true);
    }

    /** Refuses a waiting registration. The account stays disabled and the row is kept. */
    @Transactional
    public PendingRegistrationResponse reject(Long userId, String authenticatedUsername) {
        return decide(userId, authenticatedUsername, false);
    }

    private PendingRegistrationResponse decide(Long userId, String authenticatedUsername, boolean approved) {
        User decider = requireUser(authenticatedUsername);
        User applicant = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        requireDecidable(decider, applicant);

        applicant.setRegistrationStatus(approved ? RegistrationStatus.APPROVED : RegistrationStatus.REJECTED);
        applicant.setEnabled(approved);

        User saved = userRepository.save(applicant);

        auditService.recordSuccess(
                approved ? AuditAction.REGISTRATION_APPROVED : AuditAction.REGISTRATION_REJECTED,
                applicant.getLibrary().getId(), decider.getId(), AuditTarget.user(applicant.getId()));

        log.info("Registration {}: userId={} role={} libraryId={} deciderId={}",
                approved ? "approved" : "rejected", saved.getId(), saved.getRole(),
                saved.getLibrary().getId(), decider.getId());

        // Told after this transaction commits, and never before: an applicant
        // must not hear they were approved by a decision that then rolled back.
        // Staff and members get different wording, so the kind distinguishes
        // them here rather than the message guessing from a role later.
        boolean staffApplication = saved.getRole() != Role.ROLE_MEMBER;
        events.publishEvent(new NotificationRequested(
                staffApplication
                        ? (approved ? NotificationKind.STAFF_APPLICATION_APPROVED
                                : NotificationKind.STAFF_APPLICATION_REJECTED)
                        : (approved ? NotificationKind.REGISTRATION_APPROVED
                                : NotificationKind.REGISTRATION_REJECTED),
                saved.getLibrary().getId(),
                saved.getId(),
                // The subject of a registration decision is the account itself.
                saved.getId()));

        return toPending(saved);
    }

    /**
     * Whether this decider may decide this application.
     *
     * <p>The second lock. The filter chain already limits these endpoints to
     * administrators and super administrators; this is what stops an
     * administrator of one library deciding another, and what stops an
     * administrator deciding an administrator application - which only a super
     * administrator may.
     *
     * <p>A caller who may not decide is told the registration is not awaiting a
     * decision, the same answer they would get for one already settled.
     * Distinguishing them would confirm that an application exists elsewhere.
     */
    private static void requireDecidable(User decider, User applicant) {
        if (applicant.getRegistrationStatus() != RegistrationStatus.PENDING) {
            throw new RegistrationNotPendingException();
        }

        if (decider.getRole() == Role.ROLE_SUPER_ADMIN) {
            if (applicant.getRole() != Role.ROLE_ADMIN) {
                throw new RegistrationNotPendingException();
            }
            return;
        }

        boolean ownLibrary = decider.getLibrary() != null
                && applicant.getLibrary() != null
                && decider.getLibrary().getId().equals(applicant.getLibrary().getId());

        if (decider.getRole() != Role.ROLE_ADMIN
                || applicant.getRole() != Role.ROLE_LIBRARIAN
                || !ownLibrary) {
            throw new RegistrationNotPendingException();
        }
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));
    }

    private static PendingRegistrationResponse toPending(User user) {
        return new PendingRegistrationResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getLibrary() == null ? null : user.getLibrary().getId(),
                user.getLibrary() == null ? null : user.getLibrary().getName(),
                user.getCreatedAt() == null ? LocalDateTime.now() : user.getCreatedAt());
    }
}
