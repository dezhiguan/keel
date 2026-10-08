package com.keel.server.devflow;

import java.util.List;

interface HoldoutStore {
    void replace(String jobId, List<DevflowTypes.SeedCase> cases, String actor);

    List<DevflowTypes.SeedCase> cases(String jobId);

    void saveResult(String jobId, boolean passed, double score, List<HoldoutRules.TagScore> byTag);

    void clearResult(String jobId);
}

/** Used when a test does not exercise hidden cases. */
final class DiscardingHoldout implements HoldoutStore {
    @Override
    public void replace(String jobId, List<DevflowTypes.SeedCase> cases, String actor) {
    }

    @Override
    public List<DevflowTypes.SeedCase> cases(String jobId) {
        return List.of();
    }

    @Override
    public void saveResult(String jobId, boolean passed, double score, List<HoldoutRules.TagScore> byTag) {
    }

    @Override
    public void clearResult(String jobId) {
    }
}
