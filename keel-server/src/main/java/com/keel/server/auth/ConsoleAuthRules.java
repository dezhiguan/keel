package com.keel.server.auth;

public final class ConsoleAuthRules {
    private ConsoleAuthRules() {}

    public static void check(String mode, Boolean previewEnabled, boolean localProfile, boolean openForTests) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalStateException("keel.console.auth.mode 未配置");
        }
        if (!"local".equals(mode) && !"authgw".equals(mode)) {
            throw new IllegalStateException("keel.console.auth.mode 只能是 local 或 authgw");
        }
        if (previewEnabled == null) {
            throw new IllegalStateException("keel.console.preview.enabled 未配置");
        }
        if ("local".equals(mode) && !localProfile && !openForTests) {
            throw new IllegalStateException("keel.console.auth.mode=local 只能在 local profile 使用");
        }
    }
}
