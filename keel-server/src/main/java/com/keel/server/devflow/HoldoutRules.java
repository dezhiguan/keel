package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Splits and scores hidden cases. No database access. */
public final class HoldoutRules {
    /** Same default as keel gate when the manifest omits minScore. */
    public static final double MIN_SCORE = 0.85;

    private HoldoutRules() {}

    public static List<DevflowTypes.SeedCase> split(String jobId, List<DevflowTypes.SeedCase> accepted, int percent) {
        if (jobId == null || jobId.isBlank() || accepted == null || accepted.isEmpty() || percent <= 0) {
            return List.of();
        }
        var pool = new ArrayList<DevflowTypes.SeedCase>();
        for (var item : accepted) {
            if (item == null || item.caseId() == null || item.caseId().isBlank() || item.input() == null || item.expected() == null) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "用例要有编号、输入和期望");
            }
            if (pool.stream().anyMatch(existing -> item.caseId().equals(existing.caseId()))) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "用例编号重复");
            }
            pool.add(new DevflowTypes.SeedCase(item.caseId(), item.input(), item.expected(),
                    item.tags() == null ? List.of() : List.copyOf(item.tags())));
        }
        int count = pool.size() * percent / 100;
        if (count <= 0) {
            return List.of();
        }
        var shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random(jobId.hashCode()));
        return List.copyOf(shuffled.subList(0, Math.min(count, shuffled.size())));
    }

    public static Verdict judge(List<TagScore> byTag) {
        if (byTag == null || byTag.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "需要按 tag 的聚合分");
        }
        long total = 0;
        double weighted = 0;
        for (var tag : byTag) {
            if (tag == null || tag.tag() == null || tag.tag().isBlank() || tag.count() <= 0 || tag.score() < 0 || tag.score() > 1) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "聚合分要有 tag、条数，并且在 0 到 1 之间");
            }
            total += tag.count();
            weighted += tag.score() * tag.count();
        }
        double score = weighted / total;
        boolean passed = score >= MIN_SCORE && byTag.stream().allMatch(tag -> tag.score() >= MIN_SCORE);
        return new Verdict(passed, score);
    }

    public record TagScore(String tag, double score, int count) {}

    public record Verdict(boolean passed, double score) {}
}
