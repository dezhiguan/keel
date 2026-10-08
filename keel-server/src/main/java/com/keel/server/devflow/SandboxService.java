package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** Queues sandbox runs and records the report. State checks live in {@link SandboxRules}. */
@Service
public class SandboxService {
    static final Duration LIMIT = Duration.ofMinutes(10);

    private final DevflowLedger ledger;
    private final SandboxStore store;
    private final SandboxCluster cluster;

    public SandboxService(DevflowLedger ledger, SandboxStore store, SandboxCluster cluster) {
        this.ledger = ledger;
        this.store = store;
        this.cluster = cluster;
    }

    public synchronized DevflowTypes.SandboxRun start(String jobId, String repo, String ref, String actor, Instant now) {
        SandboxRules.caller(actor);
        requireJob(jobId);
        var cleanRepo = SandboxRules.repo(repo);
        var cleanRef = SandboxRules.gitRef(ref);
        pump(now);
        var running = store.active().stream().filter(run -> "RUNNING".equals(run.status())).count();
        var status = running >= SandboxRules.MAX_RUNNING ? "QUEUED" : "RUNNING";
        var run = SandboxRecord.create(store.nextRunId(), jobId, cleanRepo, cleanRef, status, actor, now, now.plus(LIMIT));
        store.insert(run);
        if ("RUNNING".equals(status)) {
            cluster.submit(run);
        }
        return view(run);
    }

    public synchronized DevflowTypes.SandboxRun get(String runId, Instant now) {
        var run = store.find(runId);
        if (run == null) {
            throw notFound();
        }
        if ("TIMEOUT".equals(run.status())) {
            throw timedOut();
        }
        if ("QUEUED".equals(run.status()) || "RUNNING".equals(run.status())) {
            if (!now.isBefore(run.deadlineAt())) {
                expire(run);
                throw timedOut();
            }
        }
        if ("RUNNING".equals(run.status())) {
            var observed = cluster.observe(run.runId());
            if (observed.phase() == SandboxCluster.Phase.DEADLINE) {
                var text = SandboxRules.truncate(observed.log());
                cluster.delete(run.runId());
                store.update(run.finish("TIMEOUT", text.text(), text.truncated()));
                pump(now);
                throw timedOut();
            }
            if (observed.phase() == SandboxCluster.Phase.SUCCEEDED || observed.phase() == SandboxCluster.Phase.FAILED) {
                var text = SandboxRules.truncate(observed.log());
                var status = observed.phase() == SandboxCluster.Phase.SUCCEEDED ? "DONE" : "FAILED";
                cluster.delete(run.runId());
                var finished = run.finish(status, text.text(), text.truncated());
                store.update(finished);
                pump(now);
                return view(finished);
            }
        }
        if ("QUEUED".equals(run.status())) {
            pump(now);
            run = store.find(runId);
        }
        return view(run);
    }

    private void expire(SandboxRecord run) {
        if ("RUNNING".equals(run.status())) {
            cluster.delete(run.runId());
        }
        store.update(run.finish("TIMEOUT", run.report(), run.truncated()));
        pump(run.deadlineAt());
    }

    private void pump(Instant now) {
        long running = store.active().stream()
                .filter(run -> "RUNNING".equals(run.status()) && now.isBefore(run.deadlineAt()))
                .count();
        for (var queued : store.active()) {
            if (!"QUEUED".equals(queued.status())) {
                continue;
            }
            if (!now.isBefore(queued.deadlineAt())) {
                store.update(queued.finish("TIMEOUT", queued.report(), queued.truncated()));
                continue;
            }
            if (running >= SandboxRules.MAX_RUNNING) {
                return;
            }
            cluster.submit(queued);
            store.update(queued.finish("RUNNING", "", false));
            running++;
        }
    }

    private void requireJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "任务号必填");
        }
        var found = ledger.jobs().stream().anyMatch(job -> jobId.equals(job.jobId()));
        if (!found) {
            throw notFound();
        }
    }

    private static DevflowTypes.SandboxRun view(SandboxRecord run) {
        return new DevflowTypes.SandboxRun(run.runId(), run.status(), run.report(), run.truncated());
    }

    private static KeelException notFound() {
        return new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    private static KeelException timedOut() {
        return new KeelException(ErrorCode.DEVFLOW_SANDBOX_TIMEOUT, ErrorCode.DEVFLOW_SANDBOX_TIMEOUT.message());
    }
}
