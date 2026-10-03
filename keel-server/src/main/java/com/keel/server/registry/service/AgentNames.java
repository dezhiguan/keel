package com.keel.server.registry.service;

import java.util.regex.Pattern;

public final class AgentNames {
    public static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,38}[a-z0-9]$");

    private AgentNames() {}

    public static String rejection(String name) {
        if (name == null || !PATTERN.matcher(name).matches()) {
            return "必须小写字母开头，2 到 40 个字符，只含小写字母、数字和连字符";
        }
        return null;
    }
}
