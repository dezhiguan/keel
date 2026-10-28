package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ConsoleSessionFilter extends OncePerRequestFilter {
    private final ConsoleAuthProperties properties;
    private final ConsoleTokenService tokens;
    private final Environment environment;
    private final ConsoleUsers consoleUsers;
    private final ServiceIdentity serviceIdentity;

    public ConsoleSessionFilter(ConsoleAuthProperties properties, ConsoleTokenService tokens, Environment environment,
                                ConsoleUsers consoleUsers, ServiceIdentity serviceIdentity) {
        this.properties = properties;
        this.tokens = tokens;
        this.environment = environment;
        this.consoleUsers = consoleUsers;
        this.serviceIdentity = serviceIdentity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put("agent", "keel-console");
        try {
            if (!request.getRequestURI().startsWith("/api/")) {
                chain.doFilter(request, response);
                return;
            }
            if (properties.openForTests()) {
                ConsolePrincipal.set(request, ConsolePrincipal.dev());
                chain.doFilter(request, response);
                return;
            }
            if (CatalogAccess.serviceAccessible(request.getMethod(), request.getRequestURI()) && serviceIdentity.applies(request)) {
                try {
                    serviceIdentity.authenticate(request);
                } catch (KeelException e) {
                    AuthResponses.write(response, e);
                    return;
                }
                var principal = request.getAttribute(ConsolePrincipal.ATTRIBUTE);
                if (principal instanceof ConsolePrincipal service) {
                    MDC.put("agent", service.username());
                }
                chain.doFilter(request, response);
                return;
            }
            String path = request.getRequestURI();
            boolean publicAuth = path.startsWith("/api/v1/auth/") && !path.equals("/api/v1/auth/logout");
            if (publicAuth) {
                chain.doFilter(request, response);
                return;
            }
            var names = ConsoleCookies.names(environment.matchesProfiles("local"));
            var verified = tokens.verify(ConsoleCookies.read(request, names, true));
            switch (verified.kind()) {
                case EXPIRED -> {
                    AuthResponses.write(response, new KeelException(ErrorCode.AUTH_TOKEN_EXPIRED, ErrorCode.AUTH_TOKEN_EXPIRED.message()));
                    return;
                }
                case AUDIENCE -> {
                    AuthResponses.write(response, new KeelException(ErrorCode.AUTH_TOKEN_AUDIENCE, ErrorCode.AUTH_TOKEN_AUDIENCE.message()));
                    return;
                }
                case INVALID -> {
                    AuthResponses.write(response, new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, "登录状态无效，请重新登录"));
                    return;
                }
                case ABSENT -> {
                    if (properties.previewOn() && read(request)) {
                        ConsolePrincipal.set(request, ConsolePrincipal.preview());
                        chain.doFilter(request, response);
                        return;
                    }
                    if (properties.previewOn()) {
                        AuthResponses.write(response, new KeelException(ErrorCode.AUTH_PREVIEW_READONLY, ErrorCode.AUTH_PREVIEW_READONLY.message()));
                        return;
                    }
                    AuthResponses.write(response, new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message()));
                    return;
                }
                case OK -> {
                    var user = consoleUsers.find(verified.username());
                    if (user.isEmpty()) {
                        AuthResponses.write(response, new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message()));
                        return;
                    }
                    var account = user.get();
                    if ("VIEWER".equals(account.platformRole()) && !viewerAllowed(request.getMethod(),path)) {
                        AuthResponses.write(response,new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN,"只读账号不允许执行此管理操作"));
                        return;
                    }
                    ConsolePrincipal.set(request, new ConsolePrincipal("USER", verified.userId(), account.username(),
                            account.displayName(), account.org(), account.platformRole(), verified.roles(), false));
                    if (!read(request) && !"1".equals(request.getHeader("X-Keel-Console"))) {
                        AuthResponses.write(response, 403, ErrorCode.AUTH_UNAUTHENTICATED, "请求没有通过页面校验，请刷新后再试", Map.of());
                        return;
                    }
                    chain.doFilter(request, response);
                }
            }
        } finally {
            MDC.remove("agent");
        }
    }

    static boolean viewerAllowed(String method,String path) {
        if("GET".equals(method) || "HEAD".equals(method))return path.equals("/api/v1/me")
                || path.equals("/api/v1/agents/keel-assistant")
                || path.matches("/api/v1/agents/keel-assistant/(elements|sessions|sessions/[A-Za-z0-9_-]+/messages)");
        return "POST".equals(method) && (path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/agents/keel-assistant/sessions")
                || path.equals("/api/v1/agents/keel-assistant/chat/stream")
                || path.matches("/api/v1/eval/runs/ev_[0-9a-f]{32}/participants"));
    }

    private static boolean read(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method);
    }
}
