package com.keel.server.provisioning;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.Audited;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/keys")
public class KeyRotationController {
    private final KeyRotationService rotation;

    public KeyRotationController(KeyRotationService rotation) {
        this.rotation = rotation;
    }

    @PostMapping("/{alias}/rotate")
    @Audited
    public R<Map<String, Object>> rotate(@PathVariable String alias) {
        var principal = ConsolePrincipal.current();
        if (principal == null || principal.readOnly() || !"ADMIN".equals(principal.platformRole())) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        var result = rotation.rotate(alias);
        return R.ok(Map.of("alias", result.alias(), "key", result.key(), "version", result.version()));
    }
}
