package com.smartlib.security;

import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;
import com.smartlib.common.util.Texts;

/**
 * Single Super Admin rule: when {@code BOOTSTRAP_SUPER_ADMIN_EMAIL} is set, that account is the platform's only
 * SUPER_ADMIN. Every other SUPER_ADMIN account is disabled at startup and can neither sign in, be approved, nor be
 * re-activated. Without the setting, SmartLib keeps its normal behaviour.
 */
@Component
public class SuperAdminPolicy {

    private final String designated;

    public SuperAdminPolicy(AppProperties properties) {
        String email = properties.bootstrap() == null ? null : properties.bootstrap().superAdminEmail();
        this.designated = email == null || email.isBlank() ? null : Texts.normalizeEmail(email.strip());
    }

    /** True when one Super Admin is configured in .env. */
    public boolean singleSuperAdmin() {
        return designated != null;
    }

    public String designatedEmail() {
        return designated;
    }

    /** Whether the account with this email may act as a SUPER_ADMIN. */
    public boolean allows(String email) {
        return designated == null || designated.equalsIgnoreCase(email);
    }
}
