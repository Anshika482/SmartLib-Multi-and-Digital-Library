package com.smartlib.security;

import org.springframework.stereotype.Component;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.library.LibraryRepository;

@Component
public class LibraryAccessGuard {

    private final LibraryRepository libraries;

    public LibraryAccessGuard(LibraryRepository libraries) {
        this.libraries = libraries;
    }

    /** Grants access to SUPER_ADMIN (any library) or approved staff of exactly this library. */
    public LibraryScope scopeFor(long libraryId) {
        SmartLibPrincipal principal = CurrentUser.require();
        if (principal.isSuperAdmin()) {
            if (!libraries.existsById(libraryId)) {
                throw ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found.");
            }
            return new LibraryScope(libraryId, principal);
        }
        if (principal.operatesLibrary(libraryId)) {
            return new LibraryScope(libraryId, principal);
        }
        throw ApiException.forbidden("You do not have access to this library.");
    }
}
