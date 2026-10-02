package com.keel.server.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts the W3C trace id into MDC so logs and response bodies carry it.
 * TODO(P1): take the id from the SkyWalking agent once service tracing is wired, instead of parsing traceparent here.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(TraceIds.MDC_KEY, traceIdOf(request.getHeader("traceparent")));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    static String traceIdOf(String traceparent) {
        if (traceparent != null) {
            var matcher = TRACEPARENT.matcher(traceparent);
            if (matcher.matches()) return matcher.group(1);
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
