package com.keel.server.integration.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** sha256(prev_hash + canonical). The canonical string is fixed so verify can recompute it. */
public final class AuditChain {
    private AuditChain() {}

    public static String hash(String prev, String agent, String env, String action, String risk, String decision,
                              String resource, String traceId) {
        var canonical = String.join("|",
                prev == null ? "" : prev,
                agent,
                env,
                action,
                risk,
                decision,
                resource == null ? "" : resource,
                traceId == null ? "" : traceId);
        return sha256(canonical);
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
