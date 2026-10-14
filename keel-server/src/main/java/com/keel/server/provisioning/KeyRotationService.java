package com.keel.server.provisioning;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.ConfigAudit;
import com.keel.server.integration.litellm.LiteLlmClient;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class KeyRotationService {
    private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]-(dev|test|staging|prod)$");

    private final LiteLlmClient gateway;
    private final SecretWriter secrets;
    private final ConfigAudit audit;

    public KeyRotationService(LiteLlmClient gateway, SecretWriter secrets, ConfigAudit audit) {
        this.gateway = gateway;
        this.secrets = secrets;
        this.audit = audit;
    }

    public LiteLlmClient.RotatedKey rotate(String alias) {
        if (alias == null || !ALIAS.matcher(alias).matches()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "别名必须是 {agent}-{env}");
        }
        var hyphen = alias.lastIndexOf('-');
        var agent = alias.substring(0, hyphen);
        var env = alias.substring(hyphen + 1);
        var target = secrets.rotationTarget(agent, env);
        audit.record(agent, env);
        var rotated = gateway.rotate(alias);
        secrets.installRotatedLlmKey(target, rotated.key());
        return rotated;
    }
}
