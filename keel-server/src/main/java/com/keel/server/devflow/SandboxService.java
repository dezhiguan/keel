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

    /** Running and queued runs. Finished rows are not counted. */
    public Activity activity() {
        int running = 0;
        int queued = 0;
        for (var run : store.active()) {
            if ("RUNNING".equals(run.status())) {
                running++;
            } else if ("QUEUED".equals(run.status())) {
                queued++;
            }
        }
        return new Activity(running, queued);
    }

    public record Activity(int running, int queued) {}

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
            try {
                cluster.submit(run);
            } catch (RuntimeException error) {
                store.update(run.finish("FAILED", "沙箱提交失败，测试未运行", false));
                throw error;
            }
        }
        return view(run);
    }

    public synchronized DevflowTypes.SandboxRun get(String runId, Instant now) {
        var run = store.find(runId);
        if (run == null) {
            throw notFound();
        }
        if ("TIMEOUT".equals(run.status())) {
            throw timedOut(run.report());
        }
        if ("RUNNING".equals(run.status())) {
            var observed = cluster.observe(run.runId());
            if (observed.phase() == SandboxCluster.Phase.DEADLINE) {
                var text = SandboxRules.truncate(observed.log());
                cluster.delete(run.runId());
                store.update(run.finish("TIMEOUT", text.text(), text.truncated()));
                pump(now);
                throw timedOut(text.text());
            }
            if (observed.phase() == SandboxCluster.Phase.MISSING) {
                store.update(run.finish("FAILED", "沙箱 Job 丢失，测试未完成", false));
                pump(now);
                return view(store.find(runId));
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
            if (!now.isBefore(run.deadlineAt())) {
                expire(run, observed.log());
                throw timedOut(store.find(runId).report());
            }
        }
        if ("QUEUED".equals(run.status())) {
            if (!now.isBefore(run.deadlineAt())) {
                expire(run, "排队超过 10 分钟，未获得沙箱运行槽位");
                throw timedOut(store.find(runId).report());
            }
            pump(now);
            run = store.find(runId);
        }
        return view(run);
    }

    private void expire(SandboxRecord run, String report) {
        if ("RUNNING".equals(run.status())) {
            cluster.delete(run.runId());
        }
        var text = SandboxRules.truncate(report);
        store.update(run.finish("TIMEOUT", text.text(), text.truncated()));
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
                store.update(queued.finish("TIMEOUT", "排队超过 10 分钟，未获得沙箱运行槽位", false));
                continue;
            }
            if (running >= SandboxRules.MAX_RUNNING) {
                return;
            }
            try {
                cluster.submit(queued);
                store.update(queued.finish("RUNNING", "", false));
                running++;
            } catch (RuntimeException error) {
                store.update(queued.finish("FAILED", "沙箱提交失败，测试未运行", false));
            }
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

    private static KeelException timedOut(String report) {
        var detail = report == null || report.isBlank() ? "" : "：" + report.substring(0, Math.min(500, report.length()));
        return new KeelException(ErrorCode.DEVFLOW_SANDBOX_TIMEOUT,
                ErrorCode.DEVFLOW_SANDBOX_TIMEOUT.message() + detail);
    }
}
