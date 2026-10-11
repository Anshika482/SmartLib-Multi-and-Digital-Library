package com.smartlib.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Helpers for reading client details from the current request (proxy headers are intentionally not trusted). */
public final class ClientInfo {
    private ClientInfo() {
    }

    public static String ip(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 255));
    }

    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    public static String currentIp() {
        return ip(currentRequest());
    }

    public static String currentRequestId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        Object id = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
