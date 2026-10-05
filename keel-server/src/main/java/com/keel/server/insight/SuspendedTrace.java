package com.keel.server.insight;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A console trace for a run that is waiting on a person and has no Langfuse observations yet. */
public final class SuspendedTrace {
    private SuspendedTrace() {}

    public static Map<String, Object> fromRun(String traceId, String runId, String agent, String prompt, String actor,
                                              String status, String reason, Instant created, Instant now) {
        var name = switch (reason == null ? "" : reason) {
            case "input_required" -> "等用户回答";
            case "handoff" -> "转人工";
            case "approval" -> "人工审批";
            default -> "人工介入";
        };
        var output = switch (status == null ? "" : status) {
            case "SUSPENDED" -> "挂起中";
            case "DONE" -> "已恢复";
            case "EXPIRED" -> "已超时";
            case "FAILED" -> "已结束";
            default -> status == null ? "" : status;
        };
        long waitMs = created == null || now == null ? 0 : Math.max(0, Duration.between(created, now).toMillis());
        return view(traceId, runId, agent, prompt, actor, name, output, "SUSPENDED".equals(status) ? waitMs : 0, null);
    }

    public static Map<String, Object> fromApproval(String traceId, String approvalId, String agent, String summary,
                                                   String actor, String subjectRef, Instant created, Instant now) {
        long waitMs = created == null || now == null ? 0 : Math.max(0, Duration.between(created, now).toMillis());
        return view(traceId, approvalId, agent, summary, actor, "人工审批", subjectRef == null ? "" : subjectRef, waitMs, approvalId);
    }

    private static Map<String, Object> view(String traceId, String id, String agent, String question, String actor,
                                            String name, String output, long waitMs, String approvalId) {
        var who = agent == null || agent.isBlank() ? "keel" : agent;
        var text = question == null || question.isBlank() ? name : question;
        var summary = new LinkedHashMap<String, Object>();
        summary.put("traceId", traceId);
        summary.put("question", text);
        summary.put("sessionId", id);
        summary.put("turn", 1);
        summary.put("userId", actor == null ? "" : actor);
        summary.put("rootAgent", who);
        summary.put("agents", new ArrayList<>(List.of(who)));
        summary.put("multiAgent", false);
        summary.put("durationMs", 0);
        summary.put("humanWaitMs", waitMs);
        summary.put("tokens", 0);
        summary.put("costCny", 0);
        summary.put("status", "ok");
        summary.put("blocked", false);
        var node = new LinkedHashMap<String, Object>();
        node.put("id", id == null || id.isBlank() ? "human" : id);
        node.put("agentKey", who);
        node.put("type", "event");
        node.put("name", name);
        node.put("startMs", 0);
        node.put("durationMs", 0);
        node.put("status", "ok");
        node.put("depth", 0);
        node.put("humanWaitLabel", label(waitMs));
        node.put("inputSummary", text);
        node.put("outputSummary", output);
        node.put("approvalId", approvalId);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("summary", summary);
        detail.put("langfuseUrl", "");
        detail.put("agents", List.of(Map.of("key", who, "name", who, "color", "#f1b44c", "kind", "human")));
        detail.put("latencyBreakdown", List.of(Map.of("label", name, "ms", 0, "agentKey", who)));
        detail.put("nodes", List.of(node));
        detail.put("edges", List.of());
        return detail;
    }

    private static String label(long waitMs) {
        if (waitMs <= 0) {
            return null;
        }
        return (waitMs / 60_000) + "m" + ((waitMs % 60_000) / 1000) + "s";
    }
}
