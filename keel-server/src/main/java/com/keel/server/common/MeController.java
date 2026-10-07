package com.keel.server.common;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MeController {
    public record CurrentUser(String userId, String displayName, String org, String platformRole,
                              List<String> roles, List<String> visibleAgents, Integer pendingApprovals,
                              String mode, Boolean readOnly) {}

    @GetMapping("/me")
    public R<CurrentUser> me() {
        ConsolePrincipal principal = ConsolePrincipal.current();
        if (principal == null) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
        }
        return R.ok(principal.toUser());
    }
}
