package com.keel.server.devflow;

import java.util.List;

interface SandboxStore {
    String nextRunId();

    void insert(SandboxRecord run);

    SandboxRecord find(String runId);

    void update(SandboxRecord run);

    /** QUEUED and RUNNING, oldest first. */
    List<SandboxRecord> active();
}
