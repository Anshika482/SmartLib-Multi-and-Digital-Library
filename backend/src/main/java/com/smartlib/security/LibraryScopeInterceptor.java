package com.smartlib.security;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;

/** Resolves and authorizes the {libraryId} of every /api/manage/libraries/{libraryId}/** request. */
@Component
public class LibraryScopeInterceptor implements HandlerInterceptor {

    public static final String ATTRIBUTE = "smartlib.libraryScope";

    private final LibraryAccessGuard guard;

    public LibraryScopeInterceptor(LibraryAccessGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String raw = vars == null ? null : vars.get("libraryId");
        if (raw == null) {
            throw ApiException.forbidden("Library scope is required.");
        }
        long libraryId;
        try {
            libraryId = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found.");
        }
        request.setAttribute(ATTRIBUTE, guard.scopeFor(libraryId));
        return true;
    }
}
