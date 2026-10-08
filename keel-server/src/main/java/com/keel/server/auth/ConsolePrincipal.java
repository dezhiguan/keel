package com.keel.server.auth;

import com.keel.server.common.MeController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

public record ConsolePrincipal(String mode, String userId, String username, String displayName, String org,
                               String platformRole, List<String> roles, boolean readOnly) {
    public static final String ATTRIBUTE = "keel.console.principal";

    public static ConsolePrincipal current() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) return null;
        return (ConsolePrincipal) servlet.getRequest().getAttribute(ATTRIBUTE);
    }

    public static void set(HttpServletRequest request, ConsolePrincipal principal) {
        request.setAttribute(ATTRIBUTE, principal);
    }

    public static ConsolePrincipal dev() {
        return new ConsolePrincipal("USER", "dev", "dev", "dev", "本地开发", "ADMIN", List.of(), false);
    }

    public static ConsolePrincipal preview() {
        return new ConsolePrincipal("PREVIEW", "", "", "预览访客", "", "VIEWER", List.of(), true);
    }

    public static ConsolePrincipal service(String agent) {
        return new ConsolePrincipal("SERVICE", agent, agent, agent, "", "", List.of(), true);
    }

    public MeController.CurrentUser toUser() {
        return new MeController.CurrentUser(userId, displayName, org, platformRole, roles, List.of(), 0, mode, readOnly);
    }
}
