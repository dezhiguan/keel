package com.keel.server.auth;

import java.util.Optional;

/** 控制台用户表。本期只有官德志，不建库、不做用户管理页。 */
public final class ConsoleUsers {
    public static final ConsoleUser GUAN = new ConsoleUser("guandezhi", "官德志", "ADMIN", "平台组");

    private ConsoleUsers() {}

    public static Optional<ConsoleUser> find(String username) {
        if (username != null && GUAN.username().equalsIgnoreCase(username)) return Optional.of(GUAN);
        return Optional.empty();
    }

    public record ConsoleUser(String username, String displayName, String platformRole, String org) {}
}
