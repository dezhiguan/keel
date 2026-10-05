package com.keel.server.approval.service;

import com.keel.server.common.TraceIds;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RunTimeoutJob {
    private final ApprovalService approvals;
    private final RunService runs;

    public RunTimeoutJob(ApprovalService approvals, RunService runs) {
        this.approvals = approvals;
        this.runs = runs;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void expire() {
        MDC.put(TraceIds.MDC_KEY, UUID.randomUUID().toString().replace("-", ""));
        MDC.put("agent", "keel");
        try {
            approvals.expireDue();
            runs.expireDue();
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
            MDC.remove("agent");
        }
    }
}
