package com.smartlib.audit;

public final class AuditActions {
    private AuditActions() {
    }

    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String LIBRARIAN_APPLIED = "LIBRARIAN_APPLIED";
    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
    public static final String VERIFICATION_RESENT = "VERIFICATION_RESENT";
    public static final String OTP_FAILED = "OTP_FAILED";
    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGIN_BLOCKED = "LOGIN_BLOCKED";
    public static final String LOGOUT = "LOGOUT";
    public static final String REFRESH_REUSE_DETECTED = "REFRESH_REUSE_DETECTED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String LIBRARIAN_APPROVED = "LIBRARIAN_APPROVED";
    public static final String LIBRARIAN_REJECTED = "LIBRARIAN_REJECTED";
    public static final String STAFF_REVOKED = "STAFF_REVOKED";
    public static final String ADMIN_INVITED = "ADMIN_INVITED";
    public static final String ADMIN_APPLIED = "ADMIN_APPLIED";
    public static final String ADMIN_APPROVED = "ADMIN_APPROVED";
    public static final String ADMIN_REJECTED = "ADMIN_REJECTED";
    public static final String SUPER_ADMIN_APPROVED = "SUPER_ADMIN_APPROVED";
    public static final String SUPER_ADMIN_REJECTED = "SUPER_ADMIN_REJECTED";
    public static final String INVITE_ACCEPTED = "INVITE_ACCEPTED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";
    public static final String MEMBERSHIP_STATUS_CHANGED = "MEMBERSHIP_STATUS_CHANGED";
    public static final String LIBRARY_CREATED = "LIBRARY_CREATED";
    public static final String LIBRARY_UPDATED = "LIBRARY_UPDATED";
    public static final String LIBRARY_STATUS_CHANGED = "LIBRARY_STATUS_CHANGED";
    public static final String POLICY_UPDATED = "POLICY_UPDATED";
    public static final String BOOK_CREATED = "BOOK_CREATED";
    public static final String BOOK_UPDATED = "BOOK_UPDATED";
    public static final String HOLDING_ADDED = "HOLDING_ADDED";
    public static final String HOLDING_UPDATED = "HOLDING_UPDATED";
    public static final String HOLDING_WITHDRAWN = "HOLDING_WITHDRAWN";
    public static final String COPIES_ADDED = "COPIES_ADDED";
    public static final String COPY_STATUS_CHANGED = "COPY_STATUS_CHANGED";
    public static final String COVER_UPLOADED = "COVER_UPLOADED";
    public static final String REQUEST_CREATED = "REQUEST_CREATED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String REQUEST_APPROVED = "REQUEST_APPROVED";
    public static final String REQUEST_REJECTED = "REQUEST_REJECTED";
    public static final String REQUEST_EXPIRED = "REQUEST_EXPIRED";
    public static final String BOOK_ISSUED = "BOOK_ISSUED";
    public static final String BOOK_RETURNED = "BOOK_RETURNED";
    public static final String FINE_CREATED = "FINE_CREATED";
    public static final String FINE_WAIVED = "FINE_WAIVED";
    public static final String PAYMENT_ORDER_CREATED = "PAYMENT_ORDER_CREATED";
    public static final String PAYMENT_VERIFIED = "PAYMENT_VERIFIED";
    public static final String PAYMENT_VERIFICATION_FAILED = "PAYMENT_VERIFICATION_FAILED";
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    public static final String RESOURCE_CREATED = "RESOURCE_CREATED";
    public static final String RESOURCE_UPDATED = "RESOURCE_UPDATED";
    public static final String RESOURCE_ARCHIVED = "RESOURCE_ARCHIVED";
    public static final String ANNOUNCEMENT_SENT = "ANNOUNCEMENT_SENT";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";
}
