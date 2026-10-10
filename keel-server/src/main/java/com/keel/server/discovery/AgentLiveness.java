package com.keel.server.discovery;

import java.time.Instant;
import java.util.List;

/**
 * Turns the instances ReconcileJob already sees into the agent card status.
 * Findings stay on the five kinds in {@link ReconcileRules}; this only chooses
 * ONLINE, DEGRADED, or OFFLINE. DRAFT and RETIRED are left to registration and retire.
 */
public final class AgentLiveness {
    private AgentLiveness() {}

    /** One instance. {@code serving} means it can take traffic right now. */
    public record Sighting(boolean serving) {}

    /**
     * Kubernetes ready is enough: the informer does not refresh {@code last_seen_at} on a stable pod.
     * Heartbeat and probe rows expire after {@link ReconcileRules#HEARTBEAT}.
     * {@code liveReady} is null when there is no watcher; otherwise it is whether that watcher still sees the pod ready.
     */
    public static boolean serving(String source, boolean ready, Instant lastSeen, Instant now, Boolean liveReady) {
        if (!ready) {
            return false;
        }
        if ("k8s".equals(source)) {
            return liveReady == null || liveReady;
        }
        return lastSeen != null && !lastSeen.plus(ReconcileRules.HEARTBEAT).isBefore(now);
    }

    public static String next(String current, List<Sighting> sightings) {
        if (current == null || "DRAFT".equals(current) || "RETIRED".equals(current)) {
            return current;
        }
        var serving = 0;
        for (var sighting : sightings) {
            if (sighting.serving()) {
                serving++;
            }
        }
        if (serving == 0) {
            return "OFFLINE";
        }
        if (serving < sightings.size()) {
            return "DEGRADED";
        }
        return "ONLINE";
    }
}
