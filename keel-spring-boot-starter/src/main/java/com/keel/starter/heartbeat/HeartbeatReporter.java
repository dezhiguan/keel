package com.keel.starter.heartbeat;

/** Sends a heartbeat only when liveness is heartbeat and KEEL_SERVER_URL is set. */
public final class HeartbeatReporter {
    public static final int INTERVAL_SECONDS = 15;

    public static boolean shouldSend(String liveness, String serverUrl) {
        return "heartbeat".equals(liveness) && serverUrl != null && !serverUrl.isBlank();
    }

    private HeartbeatReporter() {}
}
