package com.smartlib.common.error;

/** Stable machine-readable error codes returned in the {@code code} field of every error response. */
public final class ErrorCodes {
    private ErrorCodes() {
    }

    // generic
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String CONFLICT = "CONFLICT";
    public static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String UNSUPPORTED_FILE = "UNSUPPORTED_FILE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String LOGIN_REQUIRED = "LOGIN_REQUIRED";

    // auth & accounts
    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String LIBRARIAN_APPROVAL_PENDING = "LIBRARIAN_APPROVAL_PENDING";
    public static final String LIBRARIAN_REJECTED = "LIBRARIAN_REJECTED";
    /** ADMIN / SUPER_ADMIN applications (librarians keep the LIBRARIAN_* codes above). */
    public static final String APPROVAL_PENDING = "APPROVAL_PENDING";
    public static final String APPLICATION_REJECTED = "APPLICATION_REJECTED";
    public static final String STAFF_ACCESS_REVOKED = "STAFF_ACCESS_REVOKED";
    public static final String ACCOUNT_SUSPENDED = "ACCOUNT_SUSPENDED";
    public static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";
    public static final String INVALID_OTP = "INVALID_OTP";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String TOO_MANY_OTP_ATTEMPTS = "TOO_MANY_OTP_ATTEMPTS";
    public static final String NO_ACTIVE_CODE = "NO_ACTIVE_CODE";
    public static final String RESEND_COOLDOWN = "RESEND_COOLDOWN";
    public static final String TOO_MANY_RESENDS = "TOO_MANY_RESENDS";
    public static final String ALREADY_VERIFIED = "ALREADY_VERIFIED";
    public static final String INVALID_LINK = "INVALID_LINK";
    public static final String LINK_EXPIRED = "LINK_EXPIRED";
    public static final String INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN";
    public static final String CSRF_CHECK_FAILED = "CSRF_CHECK_FAILED";
    public static final String INVALID_RESET_TOKEN = "INVALID_RESET_TOKEN";
    public static final String RESET_TOKEN_EXPIRED = "RESET_TOKEN_EXPIRED";
    public static final String INVALID_INVITE = "INVALID_INVITE";
    public static final String WRONG_PASSWORD = "WRONG_PASSWORD";
    public static final String LIBRARIAN_REGISTRATION_CLOSED = "LIBRARIAN_REGISTRATION_CLOSED";
    public static final String ADMIN_REGISTRATION_CLOSED = "ADMIN_REGISTRATION_CLOSED";

    // catalogue & circulation
    public static final String LIBRARY_NOT_FOUND = "LIBRARY_NOT_FOUND";
    public static final String BOOK_NOT_FOUND = "BOOK_NOT_FOUND";
    public static final String BOOK_NOT_IN_LIBRARY = "BOOK_NOT_IN_LIBRARY";
    public static final String BOOK_UNAVAILABLE = "BOOK_UNAVAILABLE";
    public static final String REQUEST_ALREADY_EXISTS = "REQUEST_ALREADY_EXISTS";
    public static final String ALREADY_BORROWED = "ALREADY_BORROWED";
    public static final String LOAN_LIMIT_REACHED = "LOAN_LIMIT_REACHED";
    public static final String PENDING_LIMIT_REACHED = "PENDING_LIMIT_REACHED";
    public static final String OUTSTANDING_FINES = "OUTSTANDING_FINES";
    public static final String MEMBERSHIP_SUSPENDED = "MEMBERSHIP_SUSPENDED";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String NO_COPY_AVAILABLE = "NO_COPY_AVAILABLE";
    public static final String HOLDING_EXISTS = "HOLDING_EXISTS";
    public static final String HOLDING_IN_USE = "HOLDING_IN_USE";
    public static final String BOOK_SHARED = "BOOK_SHARED";
    public static final String INVALID_ISBN = "INVALID_ISBN";
    public static final String ISBN_ALREADY_EXISTS = "ISBN_ALREADY_EXISTS";

    // payments
    public static final String PAYMENTS_UNAVAILABLE = "PAYMENTS_UNAVAILABLE";
    public static final String PAYMENT_VERIFICATION_FAILED = "PAYMENT_VERIFICATION_FAILED";
    public static final String PAYMENT_PROVIDER_ERROR = "PAYMENT_PROVIDER_ERROR";
    public static final String FINE_NOT_PAYABLE = "FINE_NOT_PAYABLE";

    // resources & AI
    public static final String RESOURCE_ACCESS_DENIED = "RESOURCE_ACCESS_DENIED";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String AI_TIMEOUT = "AI_TIMEOUT";
    public static final String AI_DISABLED = "AI_DISABLED";
}
