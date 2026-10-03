package com.keel.starter.tracing;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Copies SLF4J MDC onto another thread.
 * Virtual threads do not inherit {@code InheritableThreadLocal}, so the map has to be applied explicitly.
 */
public final class MdcContext {
    private MdcContext() {}

    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> runWithContext(context, task);
    }

    public static <T> Supplier<T> wrap(Supplier<T> supplier) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                applyContext(context);
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    public static TaskDecorator taskDecorator() {
        return MdcContext::wrap;
    }

    private static void runWithContext(Map<String, String> context, Runnable task) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            applyContext(context);
            task.run();
        } finally {
            restore(previous);
        }
    }

    private static void applyContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) MDC.clear();
        else MDC.setContextMap(context);
    }

    private static void restore(Map<String, String> previous) {
        if (previous == null) MDC.clear();
        else MDC.setContextMap(previous);
    }
}
