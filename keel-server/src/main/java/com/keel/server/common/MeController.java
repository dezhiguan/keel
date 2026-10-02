package com.keel.server.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MeController {
    public record CurrentUser(String userId, String displayName, String org, String platformRole,
                              List<String> roles, List<String> visibleAgents, Integer pendingApprovals) {}

    /**
     * TODO(P0-5): read the user from the auth-gateway JWT (aud=keel-api, roles claim) once Spring Security is wired.
     * Until then every caller is a fixed local admin, so this must not be deployed beyond local dev.
     */
    @GetMapping("/me")
    public R<CurrentUser> me() {
        return R.ok(new CurrentUser("dev", "dev", "本地开发", "ADMIN", List.of(), List.of(), 0));
    }
}
