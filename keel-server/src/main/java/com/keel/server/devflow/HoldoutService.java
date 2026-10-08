package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** Hidden cases are readable only by the configured CI client. */
@Service
public class HoldoutService {
    private final HoldoutStore store;
    private final String ciClient;

    public HoldoutService(HoldoutStore store, @Value("${keel.ci.client-id:}") String ciClient) {
        this.store = store;
        this.ciClient = ciClient == null ? "" : ciClient;
    }

    public Map<String, Object> read(String jobId, String actor) {
        requireCi(actor);
        var cases = store.cases(jobId);
        if (cases.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        return Map.of("jobId", jobId, "cases", cases);
    }

    public void submit(String jobId, List<HoldoutRules.TagScore> byTag, String actor) {
        requireCi(actor);
        if (store.cases(jobId).isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var verdict = HoldoutRules.judge(byTag);
        store.saveResult(jobId, verdict.passed(), verdict.score(), List.copyOf(byTag));
        if (!verdict.passed()) {
            throw new KeelException(ErrorCode.DEVFLOW_HOLDOUT_FAILED, ErrorCode.DEVFLOW_HOLDOUT_FAILED.message());
        }
    }

    private void requireCi(String actor) {
        if (ciClient.isBlank() || actor == null || !ciClient.equals(actor)) {
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, ErrorCode.AUTH_CONSOLE_FORBIDDEN.message());
        }
    }
}
