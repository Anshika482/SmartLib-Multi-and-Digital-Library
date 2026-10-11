package com.smartlib.auth;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartlib.common.config.AppProperties;
import com.smartlib.security.PrincipalService;
import com.smartlib.security.SuperAdminPolicy;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/**
 * Single Super Admin from environment variables ({@code BOOTSTRAP_SUPER_ADMIN_EMAIL} / {@code _PASSWORD}). On every start
 * it makes sure that account exists as an active, verified SUPER_ADMIN whose password matches the configuration
 * (an existing account with that email is converted), then disables every other SUPER_ADMIN account. Runs after the
 * demo seed so a seeded demo Super Admin is disabled too. If the configured password breaks the password policy,
 * nothing is changed.
 */
@Component
@Order(3)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppProperties properties;
    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final RefreshTokenService refreshTokens;
    private final PrincipalService principals;
    private final SuperAdminPolicy superAdmins;

    public BootstrapRunner(AppProperties properties, UserRepository users, StaffAssignmentRepository staff, PasswordEncoder encoder,
                           PasswordPolicy policy, RefreshTokenService refreshTokens, PrincipalService principals, SuperAdminPolicy superAdmins) {
        this.properties = properties;
        this.users = users;
        this.staff = staff;
        this.encoder = encoder;
        this.policy = policy;
        this.refreshTokens = refreshTokens;
        this.principals = principals;
        this.superAdmins = superAdmins;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!superAdmins.singleSuperAdmin()) {
            return;
        }
        AppProperties.Bootstrap b = properties.bootstrap();
        String email = superAdmins.designatedEmail();
        // Leading/trailing spaces in .env values are almost always accidental.
        String password = b.superAdminPassword() == null ? "" : b.superAdminPassword().strip();
        String problem = policy.problem(password, email);
        if (problem != null) {
            log.error("SUPER_ADMIN setup refused (nothing changed): {}", problem);
            return;
        }
        User admin = users.findByEmail(email).orElse(null);
        if (admin == null) {
            admin = new User();
            admin.setEmail(email);
            admin.setFullName(b.superAdminName() == null || b.superAdminName().isBlank() ? "Platform Administrator" : b.superAdminName().strip());
            admin.setPasswordHash(encoder.encode(password));
            admin.setRole(UserRole.SUPER_ADMIN);
            admin.setStatus(UserStatus.ACTIVE);
            admin.setEmailVerifiedAt(Instant.now());
            admin.setTermsAcceptedAt(Instant.now());
            admin = users.save(admin);
            log.info("SUPER_ADMIN account created for {}", mask(email));
        } else {
            if (admin.getRole() != UserRole.SUPER_ADMIN) {
                log.info("Converting the existing {} account {} into the Super Admin", admin.getRole(), mask(email));
                admin.setRole(UserRole.SUPER_ADMIN);
            }
            admin.setStatus(UserStatus.ACTIVE);
            if (!admin.isEmailVerified()) {
                admin.setEmailVerifiedAt(Instant.now());
            }
            if (!encoder.matches(password, admin.getPasswordHash())) {
                admin.setPasswordHash(encoder.encode(password));
                admin.bumpTokenVersion();
                refreshTokens.revokeAll(admin.getId());
                log.info("Super Admin password updated from configuration");
            }
            // The platform owner needs no library assignment or approval record.
            staff.findByUserId(admin.getId()).ifPresent(staff::delete);
            users.save(admin);
        }
        principals.evict(admin.getId());

        List<User> others = users.findByRole(UserRole.SUPER_ADMIN).stream()
                .filter(u -> !u.getEmail().equalsIgnoreCase(email) && u.getStatus() != UserStatus.DISABLED).toList();
        for (User other : others) {
            other.setStatus(UserStatus.DISABLED);
            other.bumpTokenVersion();
            users.save(other);
            refreshTokens.revokeAll(other.getId());
            principals.evict(other.getId());
        }
        if (!others.isEmpty()) {
            log.info("Single Super Admin: disabled {} other SUPER_ADMIN account(s)", others.size());
        }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
