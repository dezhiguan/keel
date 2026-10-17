package com.keel.server.memory;

/**
 * 控制台记忆 tab 的四层状态。条数只来自调用方给出的统计，不使用原型里的示例数字。
 * 会话保留期与 {@code sdk-python/keel/lite/memory.py} 的默认 TTL（7 天）一致。没有轮次上限，也不另定 180 天保留期。
 */
public final class MemoryLayers {
    static final int SESSION_TTL_DAYS = 7;

    private MemoryLayers() {}

    public record Status(String session, String user, String task, String experience) {}

    public static Status describe(String storedLayer, String category, String name, String mode,
                                  long userCount, long taskCount, long experienceCount) {
        var kind = kind(storedLayer, category, name);
        if ("META".equals(kind)) {
            return new Status("不需要", "不需要", "只读生产履历", "不允许自写");
        }
        if ("DEV".equals(kind)) {
            return new Status("不需要", "不需要", counted("按研发任务", taskCount), candidates(experienceCount));
        }
        var chat = mode == null || mode.isBlank() || "chat".equals(mode);
        var task = "task".equals(mode) || taskCount > 0 ? counted("开启", taskCount) : "—";
        return new Status(chat ? "开启 · " + SESSION_TTL_DAYS + " 天" : "关闭", "开启", task, candidates(experienceCount));
    }

    private static String kind(String storedLayer, String category, String name) {
        if ("META".equals(storedLayer) || "meta-agent".equals(name)) return "META";
        if ("DEV".equals(storedLayer) || "dev".equals(category)) return "DEV";
        return "BIZ";
    }

    private static String counted(String label, long count) {
        return count > 0 ? label + " · " + count + " 条" : label;
    }

    private static String candidates(long count) {
        return "候选 " + Math.max(0, count) + " 条待人审";
    }
}
