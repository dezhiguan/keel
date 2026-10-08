package com.keel.server.devflow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** In-memory sandbox runs for rule tests. Production uses {@link JdbcSandboxStore}. */
class MemorySandboxStore implements SandboxStore {
    private final List<SandboxRecord> runs = new ArrayList<>();
    private int seq;

    @Override
    public String nextRunId() {
        seq += 1;
        return "SB-" + String.format("%04d", seq);
    }

    @Override
    public void insert(SandboxRecord run) {
        runs.add(run);
    }

    @Override
    public SandboxRecord find(String runId) {
        return runs.stream().filter(run -> run.runId().equals(runId)).findFirst().orElse(null);
    }

    @Override
    public void update(SandboxRecord run) {
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).runId().equals(run.runId())) {
                runs.set(i, run);
                return;
            }
        }
        throw new IllegalStateException(run.runId());
    }

    @Override
    public List<SandboxRecord> active() {
        return runs.stream()
                .filter(run -> "QUEUED".equals(run.status()) || "RUNNING".equals(run.status()))
                .sorted(Comparator.comparing(SandboxRecord::createdAt).thenComparing(SandboxRecord::runId))
                .toList();
    }
}
