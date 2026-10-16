package com.keel.server.auth;

/** Tool catalog reads an agent may call. Writes and nested console routes stay on the session. */
public final class CatalogAccess {
    private CatalogAccess() {}

    public static boolean serviceAccessible(String method, String uri) {
        return readable(method, uri) || ("POST".equals(method) && uri != null
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
