package com.keel.server.registry.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * 分类没有单独的列。向导写进 manifest 的 metadata.category 优先。
 * 没写过的，只认技术文档第 2 节点名的业务智能体和研发智能体，其余不猜。
 */
public final class AgentCategories {
    private static final Set<String> BIZ = Set.of(
            "careermate", "askdb", "offshore-wind", "cs-bot", "ops-copilot");
    private static final Set<String> DEV = Set.of(
            "prd-agent", "code-review", "test-gen", "ci-doctor", "dev-copilot");

    private AgentCategories() {}

    public static String resolve(String name, JsonNode manifest) {
        var explicit = manifest == null ? "" : manifest.path("metadata").path("category").asText("");
        if ("biz".equals(explicit) || "dev".equals(explicit)) {
            return explicit;
        }
        if (BIZ.contains(name)) {
            return "biz";
        }
        if (DEV.contains(name)) {
            return "dev";
        }
        return null;
    }
}
