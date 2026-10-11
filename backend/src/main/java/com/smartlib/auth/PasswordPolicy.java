package com.smartlib.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;

@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72; // bcrypt limit

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "12345678", "123456789", "1234567890", "qwerty123", "qwertyuiop",
            "iloveyou1", "admin123", "welcome1", "welcome123", "letmein1", "abc12345", "football1", "monkey123",
            "sunshine1", "princess1", "dragon123", "passw0rd", "p@ssw0rd", "p@ssword1", "trustno1", "baseball1",
            "superman1", "11111111", "00000000", "1q2w3e4r", "1qaz2wsx", "qwerty12", "zaq12wsx", "library1",
            "library123", "smartlib1", "smartlib123", "student1", "student123", "india123", "changeme1");

    /** Throws a VALIDATION_FAILED error with field-level messages if the password is not acceptable. */
    public void validate(String password, String confirm, String email, String passwordField, String confirmField) {
        String message = problem(password, email);
        if (message != null) {
            throw fieldError(passwordField, message);
        }
        if (confirm == null || !confirm.equals(password)) {
            throw fieldError(confirmField, "Passwords do not match");
        }
    }

    public String problem(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Password must be at least " + MIN_LENGTH + " characters";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return "Password is too long (max " + MAX_BYTES + " bytes)";
        }
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) {
            return "Password must contain at least one letter and one number";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lower)) {
            return "This password is too common. Please choose another";
        }
        if (email != null && !email.isBlank()) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (lower.equals(email.toLowerCase(Locale.ROOT)) || (local.length() >= 4 && lower.contains(local))) {
                return "Password must not contain your email address";
            }
        }
        return null;
    }

    private static ApiException fieldError(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message)
                .with("fieldErrors", List.of(Map.of("field", field, "message", message)));
    }
}
