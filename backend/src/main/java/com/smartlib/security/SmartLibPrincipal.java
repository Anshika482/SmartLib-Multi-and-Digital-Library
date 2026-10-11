package com.smartlib.security;

import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/** The authenticated caller, loaded from the database (not trusted from token claims) on each request. */
public record SmartLibPrincipal(
        long userId,
        String email,
        String fullName,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Long libraryId,
        String libraryName,
        ApprovalStatus approvalStatus,
        int tokenVersion) {

    public boolean isMember() {
        return role == UserRole.MEMBER;
    }

    public boolean isStaff() {
        return role == UserRole.LIBRARIAN || role == UserRole.ADMIN;
    }

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /** Whether this account may hold an authenticated session right now. */
    public boolean canAuthenticate() {
        if (status != UserStatus.ACTIVE || !emailVerified) {
            return false;
        }
        if (isSuperAdmin()) {
            // No application (seeded / bootstrapped / invited) or an approved one; a pending or rejected applicant never signs in.
            return approvalStatus == null || approvalStatus == ApprovalStatus.APPROVED;
        }
        return !isStaff() || (approvalStatus == ApprovalStatus.APPROVED && libraryId != null);
    }

    public boolean operatesLibrary(long id) {
        return isSuperAdmin() || (isStaff() && approvalStatus == ApprovalStatus.APPROVED && libraryId != null && libraryId == id);
    }
}
