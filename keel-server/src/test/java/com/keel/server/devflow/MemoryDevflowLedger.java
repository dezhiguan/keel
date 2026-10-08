package com.keel.server.devflow;

import java.util.ArrayList;
import java.util.List;

/** In-memory ledger for rule tests. Production uses {@link JdbcDevflowLedger}. */
class MemoryDevflowLedger implements DevflowLedger {
    private final List<DevflowTypes.Job> jobs = new ArrayList<>();
    private final List<DevflowTypes.Batch> batches = new ArrayList<>();
    private DevflowTypes.Settings settings = DevflowTypes.Settings.defaults();
    private int jobSeq;
    private int batchSeq;

    @Override
    public List<DevflowTypes.Job> jobs() {
        return List.copyOf(jobs);
    }

    @Override
    public void insert(DevflowTypes.Job job) {
        jobs.add(0, job);
    }

    @Override
    public void update(DevflowTypes.Job job) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).jobId().equals(job.jobId())) {
                jobs.set(i, job);
                return;
            }
        }
        throw new IllegalStateException(job.jobId());
    }

    @Override
    public String nextJobId() {
        jobSeq += 1;
        return "DF-" + String.format("%04d", jobSeq);
    }

    @Override
    public String nextBatchId() {
        batchSeq += 1;
        return "B-" + String.format("%02d", batchSeq);
    }

    @Override
    public List<DevflowTypes.Batch> batches() {
        return List.copyOf(batches);
    }

    @Override
    public void insertBatch(DevflowTypes.Batch batch) {
        batches.add(0, new DevflowTypes.Batch(batch.batchId(), batch.title(), batch.requester(), batch.concurrency(),
                batch.pilotJobId(), batch.pilotPassed(), batch.createdAt(), List.of()));
    }

    @Override
    public DevflowTypes.Settings settings() {
        return settings;
    }

    @Override
    public void saveSettings(DevflowTypes.Settings settings, String actor) {
        this.settings = settings;
    }

    @Override
    public void stage(String jobId, String stage, String actor, int attempt, String status, String traceId, Double cost, String summary) {
    }

    @Override
    public void artifact(String jobId, DevflowTypes.Artifact artifact) {
    }
}
