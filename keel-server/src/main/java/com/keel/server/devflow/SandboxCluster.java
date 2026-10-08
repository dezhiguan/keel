package com.keel.server.devflow;

/** Creates and watches one sandbox Job. The production implementation talks to the cluster. */
public interface SandboxCluster {
    void submit(SandboxRecord run);

    Observation observe(String runId);

    void delete(String runId);

    enum Phase { PENDING, RUNNING, SUCCEEDED, FAILED, DEADLINE, MISSING }

    record Observation(Phase phase, String log) {}
}
