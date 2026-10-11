package com.smartlib.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;

public final class CurrentUser {
    private CurrentUser() {
    }

    public static Optional<SmartLibPrincipal> optional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof SmartLibAuthentication s) {
            return Optional.of(s.getPrincipal());
        }
        return Optional.empty();
    }

    public static SmartLibPrincipal require() {
        return optional().orElseThrow(() -> ApiException.unauthorized(ErrorCodes.UNAUTHORIZED, "Please log in to continue."));
    }

    public static SmartLibPrincipal requireMember() {
        SmartLibPrincipal p = require();
        if (!p.isMember()) {
            throw ApiException.forbidden("This action is available to member accounts only.");
        }
        return p;
    }
}
