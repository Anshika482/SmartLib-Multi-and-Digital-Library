package com.smartlib.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;

@Component
public class AuthCookies {

    public static final String REFRESH_COOKIE = "smartlib_rt";
    private static final String PATH = "/api/auth";

    private final boolean secure;

    public AuthCookies(AppProperties properties) {
        this.secure = properties.security().cookieSecure();
    }

    public ResponseCookie refresh(String value, Duration ttl) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(ttl).build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(secure).sameSite("Strict").path(PATH)
                .maxAge(0).build();
    }
}
