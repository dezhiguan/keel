package com.keel.server.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

public final class ConsoleCookies {
    public static final String ACCESS = "keel_console_at";
    public static final String REFRESH = "keel_console_rt";

    private ConsoleCookies() {}

    public static Names names(boolean localProfile) {
        if (localProfile) return new Names(ACCESS, REFRESH, false);
        return new Names("__Host-" + ACCESS, "__Host-" + REFRESH, true);
    }

    public static String read(HttpServletRequest request, Names names, boolean access) {
        String primary = access ? names.access() : names.refresh();
        String fallback = access ? ACCESS : REFRESH;
        String host = access ? "__Host-" + ACCESS : "__Host-" + REFRESH;
        String value = cookie(request, primary);
        if (value != null) return value;
        value = cookie(request, host);
        if (value != null) return value;
        return cookie(request, fallback);
    }

    public static void write(HttpServletResponse response, String name, String value, boolean secure, long maxAgeSeconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Strict")
                .maxAge(Duration.ofSeconds(Math.max(maxAgeSeconds, 0)))
                .build()
                .toString());
    }

    public static void clear(HttpServletResponse response, Names names) {
        write(response, names.access(), "", names.secure(), 0);
        write(response, names.refresh(), "", names.secure(), 0);
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record Names(String access, String refresh, boolean secure) {}
}
