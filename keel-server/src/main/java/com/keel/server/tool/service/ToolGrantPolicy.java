package com.keel.server.tool.service;

import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;

/** Shared-tool grants are written by a console user after approval. Agents cannot write them. */
public final class ToolGrantPolicy {
    private ToolGrantPolicy() {}

    public static ConsolePrincipal requireConsoleWriter(ConsolePrincipal principal) {
        if (principal == null || "SERVICE".equals(principal.mode()) || principal.readOnly()) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
        return principal;
    }

    /** Null when the grant may be stored. */
    public static String rejection(String scope, String status, String agent, String versionRange) {
        if (agent == null || agent.isBlank() || versionRange == null || versionRange.isBlank() || versionRange.length() > 128) {
            return "agent 和 versionRange 不能为空";
        }
        if (!"SHARED".equals(scope)) {
            return "只有共享工具可以授权";
        }
        if ("RETIRED".equals(status)) {
            return "工具已下线";
        }
        return null;
    }
}
