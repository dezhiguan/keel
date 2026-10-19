package com.keel.llm;

import java.util.List;

/** Published logical models from the 2026-10-18 route table. */
public final class LogicalRoutes {
    public static final String QWEN = "qwen3.8-flash";
    public static final String DEEPSEEK = "deepseek-flash";

    public record Row(String logical, String condition, String env, int primaryWeight, String primary,
                      String secondary, String fallback, String gray) {
    }

    public static final List<Row> ROWS = List.of(
            new Row("chat-default", "env=prod", "prod", 80, QWEN, DEEPSEEK, DEEPSEEK, "—"),
            new Row("chat-default", "env≠prod", "dev", 100, QWEN, DEEPSEEK, "无", "—"),
            new Row("chat-cheap", "env=prod", "prod", 30, QWEN, DEEPSEEK, "按延迟", "新规则 10%"),
            new Row("long-context", "env=prod · 输入 > 200k", "prod", 0, QWEN, DEEPSEEK, QWEN, "—"));

    private LogicalRoutes() {
    }

    public static List<String> targets(String logical) {
        return isLogical(logical) ? List.of(QWEN, DEEPSEEK) : List.of();
    }

    public static boolean isLogical(String name) {
        return "chat-default".equals(name) || "chat-cheap".equals(name) || "long-context".equals(name);
    }

    public static Row match(String logical, String env) {
        var prod = "prod".equals(env);
        for (var row : ROWS) {
            if (!row.logical().equals(logical)) {
                continue;
            }
            if (prod && "prod".equals(row.env())) {
                return row;
            }
            if (!prod && "dev".equals(row.env())) {
                return row;
            }
        }
        return null;
    }

    /** roll is 0–99. A weight of 80 sends the first 80 rolls to the primary model. */
    public static String resolve(String logical, String env, int roll) {
        var row = match(logical, env);
        if (row == null) {
            return "prod".equals(env) ? DEEPSEEK : QWEN;
        }
        var bounded = Math.floorMod(roll, 100);
        if (row.primaryWeight() <= 0) {
            return row.secondary();
        }
        if (row.primaryWeight() >= 100) {
            return row.primary();
        }
        return bounded < row.primaryWeight() ? row.primary() : row.secondary();
    }
}
