package com.smartlib.common.events;

/**
 * In-process domain events. Publishers fire them inside a transaction; listeners react after commit
 * (notifications, emails) so a failed side-effect can never roll back the business action.
 */
public final class DomainEvents {
    private DomainEvents() {
    }

    /** Carries one-time secrets in memory only; they are never persisted in plaintext. */
    public record VerificationEmailRequested(long userId, String email, String fullName, String otp, String linkToken) {
    }

    public record PasswordResetRequested(long userId, String email, String fullName, String token) {
    }

    public record AdminInvited(long userId, String email, String fullName, String libraryName, String token) {
    }

    public record UserVerified(long userId) {
    }

    public record PasswordChanged(long userId) {
    }

    public record LibrarianApplied(long userId, long libraryId) {
    }

    public record LibrarianReviewed(long userId, long libraryId, boolean approved, String reason) {
    }

    /** An ADMIN (libraryId set) or SUPER_ADMIN (libraryId null) application. */
    public record AdminApplied(long userId, Long libraryId, String role) {
    }

    public record AdminReviewed(long userId, Long libraryId, String role, boolean approved, String reason) {
    }

    public record RequestCreated(long requestId) {
    }

    public record RequestApproved(long requestId) {
    }

    public record RequestRejected(long requestId) {
    }

    public record RequestExpired(long requestId) {
    }

    public record BookIssued(long borrowingId) {
    }

    public record BookReturned(long borrowingId) {
    }

    public record FineCreated(long fineId) {
    }

    public record FineWaived(long fineId) {
    }

    public record PaymentSucceeded(long paymentId) {
    }

    public record ResourcePublished(long resourceId) {
    }

    public record Announcement(long libraryId, String title, String body, boolean sendEmail, long authorId) {
    }

    public record DueReminder(long borrowingId, String kind, long daysOverdue) {
    }
}
