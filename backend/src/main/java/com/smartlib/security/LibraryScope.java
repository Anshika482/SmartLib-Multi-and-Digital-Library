package com.smartlib.security;

import com.smartlib.common.error.ApiException;

/**
 * Proof that the caller may operate on {@link #libraryId()}. Only {@link LibraryAccessGuard} can create one, so
 * services that take a LibraryScope cannot be called with an unchecked library id.
 */
public final class LibraryScope {

    private final long libraryId;
    private final SmartLibPrincipal principal;

    LibraryScope(long libraryId, SmartLibPrincipal principal) {
        this.libraryId = libraryId;
        this.principal = principal;
    }

    public long libraryId() {
        return libraryId;
    }

    public SmartLibPrincipal principal() {
        return principal;
    }

    public long actorId() {
        return principal.userId();
    }

    public boolean isAdmin() {
        return principal.isAdmin() || principal.isSuperAdmin();
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw ApiException.forbidden("Only library administrators can perform this action.");
        }
    }
}
