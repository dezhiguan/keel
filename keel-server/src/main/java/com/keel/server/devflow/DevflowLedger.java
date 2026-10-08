package com.keel.server.devflow;

import java.util.List;

/** Job ledger. The state rules do not live here. */
public interface DevflowLedger {
    List<DevflowTypes.Job> jobs();

    void insert(DevflowTypes.Job job);

    void update(DevflowTypes.Job job);

    String nextJobId();

    String nextBatchId();

    List<DevflowTypes.Batch> batches();

    void insertBatch(DevflowTypes.Batch batch);

    DevflowTypes.Settings settings();

    void saveSettings(DevflowTypes.Settings settings, String actor);

    void stage(String jobId, String stage, String actor, int attempt, String status, String traceId, Double cost, String summary);

    void artifact(String jobId, DevflowTypes.Artifact artifact);
}
