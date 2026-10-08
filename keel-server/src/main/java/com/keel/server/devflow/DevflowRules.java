package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Devflow transitions. No database access, so the rules can be tested without Docker. */
public final class DevflowRules {
    private static final List<String> STAGES = List.of(
            "SPEC", "H1", "EVAL", "H2", "H3", "BUILD", "REVIEW", "GATE", "H4", "RELEASE", "WATCH");
    private static final Set<String> BUILDING = Set.of("BUILD", "REVIEW", "GATE");
    private static final Set<String> WAITING = Set.of("H1", "H2", "H4");

    private DevflowRules() {}

    public static void rejectLineage(String layer, String kind, String target, String serviceAgent) {
        if ("CHANGE".equals(kind) && "meta-agent".equals(target)) {
            throw lineage();
        }
        if (serviceAgent == null || serviceAgent.isBlank()) {
            return;
        }
        if (serviceAgent.equals(target)) {
            throw lineage();
        }
        if ("DEV".equals(layer) && !"meta-agent".equals(serviceAgent)) {
            throw lineage();
        }
        if ("BIZ".equals(layer) && !"dev-lead".equals(serviceAgent)) {
            throw lineage();
        }
    }

    public static String mode(String layer, String requested) {
        if ("DEV".equals(layer)) {
            return "COLLAB";
        }
        return requested == null || requested.isBlank() ? "AUTO" : requested;
    }

    public static String producer(String layer) {
        return "DEV".equals(layer) ? "meta-agent" : "dev-lead";
    }

    public static boolean canTakeover(DevflowTypes.Job job) {
        boolean building = "RUN".equals(job.status()) && BUILDING.contains(job.stage());
        return building || "FAIL".equals(job.status()) || (job.needReview() && "WAIT".equals(job.status()));
    }

    public static boolean canCancel(DevflowTypes.Job job) {
        return Set.of("RUN", "WAIT", "HUMAN", "QUEUED").contains(job.status()) && !"WATCH".equals(job.stage());
    }

    public static String reviewGate(DevflowTypes.Job job) {
        if (!"WAIT".equals(job.status())) {
            throw notResumable();
        }
        if (WAITING.contains(job.stage())) {
            return job.stage();
        }
        if (job.needReview()) {
            return "PR";
        }
        throw notResumable();
    }

    public static DevflowTypes.Step advance(DevflowTypes.Job job, String reportStatus, Double cost, String summary, String actor, String at) {
        if (!"OK".equals(reportStatus) && !"FAILED".equals(reportStatus)) {
            throw invalid("阶段结论只能是 OK 或 FAILED");
        }
        if (summary == null || summary.isBlank()) {
            throw invalid("阶段结论不能为空");
        }
        double add = cost == null ? 0 : cost;
        if (add < 0) {
            throw invalid("花费不能为负");
        }
        if (job.spentCny() + add > job.budgetCny()) {
            throw new KeelException(ErrorCode.DEVFLOW_BUDGET_EXCEEDED, ErrorCode.DEVFLOW_BUDGET_EXCEEDED.message());
        }
        double spent = job.spentCny() + add;
        var events = event(job, actor, at, summary);
        if ("FAILED".equals(reportStatus)) {
            boolean fixable = BUILDING.contains(job.stage());
            int rounds = job.fixRounds() + (fixable ? 1 : 0);
            boolean exhausted = !fixable || rounds > job.maxFixRounds();
            var failed = job.withProgress(exhausted ? "FAIL" : job.status(), job.stage(), spent, rounds, events);
            return new DevflowTypes.Step(failed, exhausted);
        }
        var next = nextStage(job.stage());
        if (next == null) {
            return new DevflowTypes.Step(job.withProgress("DONE", job.stage(), spent, job.fixRounds(), events), false);
        }
        var status = WAITING.contains(next) ? "WAIT" : "RUN";
        return new DevflowTypes.Step(job.withProgress(status, next, spent, job.fixRounds(), events), false);
    }

    /** Null when there is nothing to show. The console keeps its placeholder in that case. */
    public static Map<String, Object> cost(List<DevflowTypes.Job> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return null;
        }
        var byAgent = new LinkedHashMap<String, double[]>();
        var layers = new LinkedHashMap<String, String>();
        for (var job : jobs) {
            byAgent.computeIfAbsent(job.producerAgent(), key -> new double[1])[0] += job.spentCny();
            layers.put(job.producerAgent(), "meta-agent".equals(job.producerAgent()) ? "META" : "DEV");
        }
        var agents = new ArrayList<Map<String, Object>>();
        byAgent.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue()[0], left.getValue()[0]))
                .forEach(entry -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("agent", entry.getKey());
                    row.put("layer", layers.get(entry.getKey()));
                    row.put("costCny", round(entry.getValue()[0]));
                    agents.add(row);
                });
        var top = jobs.stream()
                .sorted((left, right) -> Double.compare(right.spentCny(), left.spentCny()))
                .limit(5)
                .map(job -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("jobId", job.jobId());
                    row.put("title", job.title());
                    row.put("spentCny", round(job.spentCny()));
                    row.put("budgetCny", round(job.budgetCny()));
                    return row;
                })
                .toList();
        double total = jobs.stream().mapToDouble(DevflowTypes.Job::spentCny).sum();
        var body = new LinkedHashMap<String, Object>();
        body.put("totalCny", round(total));
        body.put("byAgent", agents);
        body.put("topJobs", top);
        return body;
    }

    private static List<DevflowTypes.Event> event(DevflowTypes.Job job, String actor, String at, String summary) {
        var events = new ArrayList<>(job.events());
        events.add(new DevflowTypes.Event(at, actor, summary));
        return events;
    }

    private static String nextStage(String stage) {
        int found = STAGES.indexOf(stage);
        if (found < 0 || found + 1 >= STAGES.size()) {
            return null;
        }
        return STAGES.get(found + 1);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static KeelException lineage() {
        return new KeelException(ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN, ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN.message());
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }

    private static KeelException notResumable() {
        return new KeelException(ErrorCode.RUN_NOT_RESUMABLE, ErrorCode.RUN_NOT_RESUMABLE.message());
    }
}
