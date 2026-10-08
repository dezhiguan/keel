package com.keel.server.auth;

/** Tool catalog reads an agent may call. Writes and nested console routes stay on the session. */
public final class CatalogAccess {
    private CatalogAccess() {}

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
