package com.smartlib.common.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly-typed view of the {@code smartlib.*} configuration tree. */
@ConfigurationProperties(prefix = "smartlib")
public record AppProperties(
        String frontendBaseUrl,
        List<String> corsAllowedOrigins,
        Jwt jwt,
        Security security,
        Verification verification,
        PasswordReset passwordReset,
        Mail mail,
        Storage storage,
        Gemini gemini,
        Razorpay razorpay,
        Covers covers,
        Seed seed,
        Bootstrap bootstrap) {

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl) {
    }

    public record Security(String tokenPepper, boolean cookieSecure, Integer bcryptStrength) {
        public int bcryptStrengthOrDefault() {
            return bcryptStrength == null ? 12 : bcryptStrength;
        }
    }

    public record Verification(Duration otpTtl, Duration linkTtl, int maxAttempts, Duration resendCooldown,
                               int maxResendsPerDay, Duration unverifiedRetention) {
    }

    public record PasswordReset(Duration ttl) {
    }

    public record Mail(String from, String fromName, boolean async) {
    }

    public record Storage(String dir) {
    }

    /** {@code thinkingLevel} (Gemini 3+, e.g. "minimal") wins over the older {@code thinkingBudget}; both keep replies fast. */
    public record Gemini(String apiKey, String model, String baseUrl, Integer thinkingBudget, String thinkingLevel, double temperature,
                         int maxOutputTokens, Duration connectTimeout, Duration firstChunkTimeout,
                         Duration totalTimeout, int historyMessages) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** {@code demoMode}: use the built-in demo gateway when no Razorpay keys are set (no real money moves). */
    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl, boolean demoMode) {
        public boolean configured() {
            return keyId != null && !keyId.isBlank() && keySecret != null && !keySecret.isBlank();
        }
    }

    public record Covers(boolean enabled, String openLibraryUrl, String openLibraryCoversUrl, String googleBooksUrl) {
    }

    /** {@code demoLogin}: show the seeded demo accounts (with their password) on the login page. Never enable in production. */
    public record Seed(boolean demo, String demoPassword, boolean demoLogin) {
    }

    public record Bootstrap(String superAdminEmail, String superAdminPassword, String superAdminName) {
    }
}
