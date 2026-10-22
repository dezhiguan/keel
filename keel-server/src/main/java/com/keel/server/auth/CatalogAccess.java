package com.keel.server.auth;

/** Tool catalog reads an agent may call. Writes and nested console routes stay on the session. */
public final class CatalogAccess {
    private CatalogAccess() {}

    public static boolean serviceAccessible(String method, String uri) {
        if (uri != null && (("GET".equals(method) && uri.matches("/api/v1/devflow/holdout/DF-[0-9]+"))
                || ("POST".equals(method) && (uri.equals("/api/v1/devflow/holdout-results")
                    || uri.matches("/api/v1/devflow/ci/jobs/DF-[0-9]+/(context|prepare|ready|complete|status)")))))
            return true;
        return (uri != null && uri.matches("/api/v1/agents/[a-z][a-z0-9-]*/memories/session-summary")
                    && ("GET".equals(method) || "POST".equals(method)))
                || ("POST".equals(method) && uri != null && uri.matches("/api/v1/eval/runs/ev_[a-f0-9]{32}/(context|turns|progress)")) || readable(method, uri) || ("POST".equals(method) && uri != null
                && uri.matches("/api/v1/tools/[a-z][a-z0-9.-]+/invoke"))
                || ("GET".equals(method) && uri != null
                    && uri.matches("/api/v1/agents/[a-z][a-z0-9-]*/skills/[a-z][a-z0-9-]*"))
                || (uri != null && uri.matches("/api/v1/agents/[a-z][a-z0-9-]*/memories(?:/recall|/[0-9]+)?")
                    && ("GET".equals(method) || "POST".equals(method) || "DELETE".equals(method)));
    }

    public static boolean readable(String method, String uri) {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if ("/api/v1/tools".equals(uri)) {
            return true;
        }
        var prefix = "/api/v1/tools/";
        if (uri == null || !uri.startsWith(prefix)) {
            return false;
        }
        var name = uri.substring(prefix.length());
        return !name.isEmpty() && !name.contains("/");
    }
}
