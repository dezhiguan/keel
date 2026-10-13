package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Pure checks for a sandbox run. No cluster and no database. */
final class SandboxRules {
    static final int REPORT_BYTES = 64 * 1024;
    static final int MAX_RUNNING = 4;
    static final Set<String> CALLERS = Set.of("meta-agent", "dev-agent", "eval-agent");

    private SandboxRules() {}

    static void caller(String actor) {
        if (!CALLERS.contains(actor)) {
            throw new KeelException(ErrorCode.DEVFLOW_GRANT_MISSING, ErrorCode.DEVFLOW_GRANT_MISSING.message());
        }
    }

    static String repo(String repo) {
        if (repo == null || !repo.matches("^[a-z][a-z0-9-]{0,62}$")) {
            throw invalid("仓库必须是 keel-agents 组织下的仓库名");
        }
        return repo;
    }

    static String gitRef(String ref) {
        if (ref == null || !ref.matches("[0-9a-f]{40}")) {
            throw invalid("沙箱只接受不可变的 40 位 Git commit SHA");
        }
        return ref;
    }

    static Text truncate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Text("", false);
        }
        var bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= REPORT_BYTES) {
            return new Text(raw, false);
        }
        var end = REPORT_BYTES;
        while (end > 0 && (bytes[end] & 0b1100_0000) == 0b1000_0000) {
            end--;
        }
        return new Text(new String(bytes, 0, end, StandardCharsets.UTF_8), true);
    }

    static String jobName(String runId) {
        return runId.toLowerCase(java.util.Locale.ROOT);
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }

    record Text(String text, boolean truncated) {}
}
