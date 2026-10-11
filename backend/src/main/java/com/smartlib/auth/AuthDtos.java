package com.smartlib.auth;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.UserRole;

public final class AuthDtos {
    private AuthDtos() {
    }

    public static final String PHONE_REGEX = "^$|^\\+?[0-9][0-9 -]{6,18}[0-9]$";

    public record RegisterMemberRequest(
            @NotBlank(message = "Full name is required") @Size(min = 2, max = 120, message = "Full name must be 2-120 characters") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
            @Pattern(regexp = PHONE_REGEX, message = "Enter a valid phone number") String phone,
            @NotBlank(message = "Password is required") String password,
            @NotBlank(message = "Please confirm your password") String confirmPassword,
            @AssertTrue(message = "You must accept the terms to continue") boolean acceptTerms) {
    }

    public record RegisterLibrarianRequest(
            @NotBlank(message = "Full name is required") @Size(min = 2, max = 120, message = "Full name must be 2-120 characters") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
            @Pattern(regexp = PHONE_REGEX, message = "Enter a valid phone number") String phone,
            @NotBlank(message = "Password is required") String password,
            @NotBlank(message = "Please confirm your password") String confirmPassword,
            @NotNull(message = "Please choose the library you work at") Long libraryId,
            @Size(max = 64, message = "Employee/staff ID is too long") String employeeRef,
            @Size(max = 1000, message = "Note is too long (max 1000 characters)") String applicationNote,
            @AssertTrue(message = "You must accept the terms to continue") boolean acceptTerms) {
    }

    /** Application to administer one library. Approved only by a SUPER_ADMIN after email verification. */
    public record RegisterAdminRequest(
            @NotBlank(message = "Full name is required") @Size(min = 2, max = 120, message = "Full name must be 2-120 characters") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") @Size(max = 254) String email,
            @NotBlank(message = "Phone number is required") @Pattern(regexp = PHONE_REGEX, message = "Enter a valid phone number") String phone,
            @NotBlank(message = "Password is required") String password,
            @NotBlank(message = "Please confirm your password") String confirmPassword,
            @NotNull(message = "Please choose the library you will administer") Long libraryId,
            @NotBlank(message = "Designation is required") @Size(max = 100, message = "Designation is too long (max 100 characters)") String designation,
            @Size(max = 64, message = "Employee/staff ID is too long") String employeeRef,
            @NotBlank(message = "Please explain why you need admin access")
            @Size(min = 20, max = 1000, message = "Please write between 20 and 1000 characters") String applicationNote,
            @AssertTrue(message = "You must confirm that you are authorised to administer this library") boolean confirmAuthority,
            @AssertTrue(message = "You must accept the terms to continue") boolean acceptTerms) {
    }

    public record RegistrationResponse(String email, String role, long otpExpiresInSeconds, long resendAvailableInSeconds,
                                       String nextStep, String message) {
    }

    public record LoginRequest(@NotBlank(message = "Email is required") String email,
                               @NotBlank(message = "Password is required") String password) {
    }

    public record LibraryRef(long id, String name, String slug) {
    }

    public record Me(long id, String email, String fullName, String phone, UserRole role, boolean emailVerified,
                     LibraryRef library, ApprovalStatus approvalStatus, Instant createdAt) {
    }

    public record LoginResponse(String accessToken, long expiresInSeconds, Me user) {
    }

    public record VerifyOtpRequest(@NotBlank(message = "Email is required") String email,
                                   @NotBlank(message = "Enter the 6-digit code") @Pattern(regexp = "^[0-9]{6}$", message = "The code must be exactly 6 digits") String otp) {
    }

    public record VerifyLinkRequest(@NotBlank(message = "Verification token is missing") @Size(max = 100) String token) {
    }

    public record EmailRequest(@NotBlank(message = "Email is required") @Email(message = "Enter a valid email address") String email) {
    }

    public record VerificationResult(String status, String email, UserRole role, String nextStep, String message) {
    }

    public record ResendResponse(String message, long otpExpiresInSeconds, long resendAvailableInSeconds) {
    }

    public record MessageResponse(String message) {
    }

    public record TokenRequest(@NotBlank @Size(max = 100) String token) {
    }

    public record TokenValidity(boolean valid, String reason, String email, String fullName, String libraryName) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 100) String token,
                                       @NotBlank(message = "Password is required") String newPassword,
                                       @NotBlank(message = "Please confirm your password") String confirmPassword) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "Current password is required") String currentPassword,
                                        @NotBlank(message = "New password is required") String newPassword,
                                        @NotBlank(message = "Please confirm your password") String confirmPassword) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Full name is required") @Size(min = 2, max = 120, message = "Full name must be 2-120 characters") String fullName,
            @Pattern(regexp = PHONE_REGEX, message = "Enter a valid phone number") String phone) {
    }

    public record AuthPolicy(int otpLength, long otpTtlSeconds, long linkTtlSeconds, long resendCooldownSeconds,
                             int maxResendsPerDay, int maxOtpAttempts, int passwordMinLength, int passwordMaxLength,
                             boolean librarianRegistrationOpen, boolean adminRegistrationOpen) {
    }
}
