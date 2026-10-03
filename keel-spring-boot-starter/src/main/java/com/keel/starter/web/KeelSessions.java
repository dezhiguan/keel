package com.keel.starter.web;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-flight invoke runs. Removed from {@code onCompletion}, {@code onTimeout}, and {@code onError}. */
public final class KeelSessions {
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public void begin(String runId) { active.add(runId); }

    public void end(String runId) { active.remove(runId); }

    public boolean active(String runId) { return active.contains(runId); }

    public int size() { return active.size(); }
}
