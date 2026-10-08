package com.keel.server.devflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory hidden cases for rule tests. */
class MemoryHoldoutStore implements HoldoutStore {
    private final Map<String, List<DevflowTypes.SeedCase>> saved = new LinkedHashMap<>();
    private final Map<String, Boolean> results = new LinkedHashMap<>();

    @Override
    public void replace(String jobId, List<DevflowTypes.SeedCase> cases, String actor) {
        saved.put(jobId, new ArrayList<>(cases));
    }

    @Override
    public List<DevflowTypes.SeedCase> cases(String jobId) {
        return List.copyOf(saved.getOrDefault(jobId, List.of()));
    }

    @Override
    public void saveResult(String jobId, boolean passed, double score, List<HoldoutRules.TagScore> byTag) {
        results.put(jobId, passed);
    }

    @Override
    public void clearResult(String jobId) {
        results.remove(jobId);
    }

    Boolean result(String jobId) {
        return results.get(jobId);
    }
}
