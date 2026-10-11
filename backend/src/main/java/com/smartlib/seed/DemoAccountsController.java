package com.smartlib.seed;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.config.AppProperties;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserStatus;

/**
 * One-click demo logins for the login page, so visitors can explore every role. Disabled unless
 * {@code DEMO_LOGIN_ENABLED=true}; when disabled it reveals nothing. Only seeded accounts that exist and can sign in are
 * listed. Never enable in production: the response contains the shared demo password.
 */
@RestController
@RequestMapping("/api/public/demo-accounts")
public class DemoAccountsController {

    public record DemoAccount(String role, String label, String email, String description) {
    }

    public record DemoAccounts(boolean enabled, String password, List<DemoAccount> accounts) {
    }

    /**
     * Accounts created by {@link DemoDataSeeder}. No Super Admin: the platform's single Super Admin is the real account
     * configured in .env, whose password must never be shown.
     */
    private static final List<DemoAccount> ACCOUNTS = List.of(
            new DemoAccount("MEMBER", "Member", "member@smartlib.demo", "Borrow books, track loans and pay fines"),
            new DemoAccount("LIBRARIAN", "Librarian", "librarian.central@smartlib.demo", "Central Library: requests, loans and books"),
            new DemoAccount("ADMIN", "Admin", "admin.central@smartlib.demo", "Central Library: staff, members and settings"));

    private final AppProperties properties;
    private final UserRepository users;

    public DemoAccountsController(AppProperties properties, UserRepository users) {
        this.properties = properties;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<DemoAccounts> demoAccounts() {
        String password = properties.seed().demoPassword();
        List<DemoAccount> usable = !properties.seed().demoLogin() || password == null || password.isBlank() ? List.of()
                : ACCOUNTS.stream().filter(a -> users.findByEmail(a.email()).filter(DemoAccountsController::canSignIn).isPresent()).toList();
        DemoAccounts body = usable.isEmpty() ? new DemoAccounts(false, null, List.of()) : new DemoAccounts(true, password, usable);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static boolean canSignIn(User user) {
        return user.getStatus() == UserStatus.ACTIVE && user.isEmailVerified();
    }
}
