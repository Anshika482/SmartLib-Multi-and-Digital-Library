package com.smartlib.support;

import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.library.Library;
import com.smartlib.library.LibraryPolicy;
import com.smartlib.library.LibraryPolicyRepository;
import com.smartlib.library.LibraryRepository;
import com.smartlib.library.LibraryStatus;
import com.smartlib.security.JwtService;
import com.smartlib.security.PrincipalService;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/** Fast fixtures created directly through repositories (bypassing the HTTP flows under test). */
@Component
public class TestData {

    public static final String PASSWORD = "Str0ngPass!42";

    private final UserRepository users;
    private final LibraryRepository libraries;
    private final LibraryPolicyRepository policies;
    private final StaffAssignmentRepository staff;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final PrincipalService principals;

    public TestData(UserRepository users, LibraryRepository libraries, LibraryPolicyRepository policies,
                    StaffAssignmentRepository staff, PasswordEncoder encoder, JwtService jwt, PrincipalService principals) {
        this.users = users;
        this.libraries = libraries;
        this.policies = policies;
        this.staff = staff;
        this.encoder = encoder;
        this.jwt = jwt;
        this.principals = principals;
    }

    @Transactional
    public Library library(String code, String name) {
        Library l = new Library();
        l.setCode(code);
        l.setSlug(code.toLowerCase());
        l.setName(name);
        l.setCity("Pune");
        l.setStatus(LibraryStatus.ACTIVE);
        l.setTimezone("Asia/Kolkata");
        libraries.save(l);
        policies.save(LibraryPolicy.defaultsFor(l.getId()));
        return l;
    }

    @Transactional
    public User user(String email, UserRole role, boolean verified) {
        User u = new User();
        u.setEmail(email);
        u.setFullName("Test " + role.name().toLowerCase());
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerifiedAt(verified ? Instant.now() : null);
        return users.save(u);
    }

    public User member(String email) {
        return user(email, UserRole.MEMBER, true);
    }

    @Transactional
    public User staff(String email, UserRole role, long libraryId, ApprovalStatus status) {
        User u = user(email, role, true);
        StaffAssignment a = new StaffAssignment();
        a.setUserId(u.getId());
        a.setLibraryId(libraryId);
        a.setApprovalStatus(status);
        staff.save(a);
        return u;
    }

    public User superAdmin(String email) {
        return user(email, UserRole.SUPER_ADMIN, true);
    }

    /** Issues a real access token for a fixture user (same code path as login). */
    public String token(User user) {
        principals.evict(user.getId());
        User fresh = users.findById(user.getId()).orElseThrow();
        return jwt.issueAccessToken(principals.toPrincipal(fresh));
    }
}
