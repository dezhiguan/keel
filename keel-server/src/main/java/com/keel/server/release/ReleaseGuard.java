package com.keel.server.release;

import com.fasterxml.jackson.databind.JsonNode;

/** A devflow-produced agent may ship only after a person approved git.pr.merge. */
public final class ReleaseGuard {
    private ReleaseGuard() {}

    /** Null when the release may continue. */
    public static String rejection(String devflowJobId, boolean approvedMerge) {
        if (devflowJobId == null || devflowJobId.isBlank() || approvedMerge) {
            return null;
        }
        return "由智能体生产的员工发布必须有已批准的 git.pr.merge 审批单";
    }

    /** A caller-supplied gate ID is never evidence for a produced employee. */
    public static String evidenceRejection(JsonNode state, String env, String gateRunId, String image,
                                           boolean holdoutPassed, boolean approvedMerge) {
        if (state == null || !state.isObject()) return "缺少元智能体生产任务的可信执行记录";
        var phase = state.path("phase").asText();
        if (!"STAGING_GATE_PASSED".equals(phase) && !"RELEASING".equals(phase)
                && !"WATCHING".equals(phase)) return "候选员工尚未通过 staging 门禁";
        if (!state.path("humanReview").path("approved").asBoolean(false)
                || !state.path("modelReview").path("approved").asBoolean(false)) {
            return "代码评审及人工 PR 审批尚未通过";
        }
        var gate = "prod".equals(env) ? state.path("prodGate") : state.path("gate");
        var expectedSha = "prod".equals(env) ? state.path("mergeSha").asText()
                : state.path("delivery").path("sha").asText();
        if (!gate.path("passed").asBoolean(false) || !holdoutPassed
                || !gateRunId.equals(gate.path("runId").asText())
                || !image.equals(gate.path("image").asText())
                || !env.equals(gate.path("env").asText())
                || !expectedSha.equals(gate.path("sha").asText())) {
            return "门禁或隐藏考题缺少与候选提交绑定的通过证据";
        }
        if ("prod".equals(env) && (!approvedMerge || !"RELEASING".equals(phase))) {
            return "生产发布必须先获 H4 git.pr.merge 审批并进入发布阶段";
        }
        return null;
    }
}
