package com.keel.server.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Five finding kinds. A healthy online agent produces nothing. */
public final class ReconcileRules {
    public static final Duration HEARTBEAT = Duration.ofSeconds(45);
    public static final Duration IDLE = Duration.ofDays(7);

    public enum Kind {
        OFFLINE, UNREGISTERED, ZOMBIE, RETIRE_INCOMPLETE, VERSION_MISMATCH
    }

    public record Input(String status, boolean registered, boolean running, boolean ready,
                        Instant lastSeen, Instant now, String instanceVersion, String registeredVersion,
                        Boolean langfuseTraffic, Boolean gatewayTraffic) {}

    public static Optional<Kind> classify(Input input) {
        if (!input.registered && input.running) {
            return Optional.of(Kind.UNREGISTERED);
        }
        if ("RETIRED".equals(input.status) && input.running) {
            return Optional.of(Kind.RETIRE_INCOMPLETE);
        }
        if ("ONLINE".equals(input.status)) {
            var stale = input.lastSeen == null || input.lastSeen.plus(HEARTBEAT).isBefore(input.now);
            if (!input.ready || stale) {
                return Optional.of(Kind.OFFLINE);
            }
            if (input.instanceVersion != null && input.registeredVersion != null
                    && !input.instanceVersion.equals(input.registeredVersion)) {
                return Optional.of(Kind.VERSION_MISMATCH);
            }
            if (input.langfuseTraffic == null && input.gatewayTraffic == null) {
                return Optional.empty();
            }
            var traffic = Boolean.TRUE.equals(input.langfuseTraffic) || Boolean.TRUE.equals(input.gatewayTraffic);
            if (!traffic) {
                return Optional.of(Kind.ZOMBIE);
            }
        }
        return Optional.empty();
    }

    private ReconcileRules() {}
}
