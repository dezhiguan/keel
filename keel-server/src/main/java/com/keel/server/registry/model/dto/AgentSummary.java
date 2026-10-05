package com.keel.server.registry.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.registry.model.AgentCategories;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;

import java.util.ArrayList;
import java.util.List;

/** contracts/console-api.openapi.yaml#/components/schemas/AgentSummary. */
public record AgentSummary(
        String name,
        String displayName,
        String category,
        String language,
        String runtime,
        String template,
        String env,
        String version,
        AgentStatus status,
        String statusNote,
        Boolean gatePassed,
        String ownerOrg,
        String ownerUser,
        Long calls24h,
        Long callsTotal,
        Double p95Seconds,
        Double costCny,
        Double dailyBudgetCny,
        Double score,
        String instances,
        Boolean multiAgent,
        Integer delegateCount) {

    public static AgentSummary of(Agent agent) {
        return of(agent, null, null, null);
    }

    /**
     * 分类、环境、版本、日预算、编排个数来自登记数据。
     * 调用量、成本和评分由 insight 再填，这里保持 null，避免把没读到的数写成 0。
     */
    public static AgentSummary of(Agent agent, AgentVersion version, String instances, JsonNode manifest) {
        var delegates = delegates(manifest);
        return new AgentSummary(agent.getName(), agent.getDisplayName(),
                AgentCategories.resolve(agent.getName(), manifest),
                agent.getLanguage(), agent.getRuntime(), text(manifest, "spec", "runtime", "template"),
                version == null ? null : version.getEnv(),
                version == null ? null : version.getVersion(),
                agent.getStatus(), statusNote(agent.getStatus(), instances), null,
                agent.getOwnerOrg(), agent.getOwnerUser(),
                null, null, null, null, budget(manifest), null, instances,
                delegates.isEmpty() ? null : Boolean.TRUE, delegates.isEmpty() ? null : delegates.size());
    }

    public AgentSummary withUsage(Long calls24h, Long callsTotal, Double p95Seconds, Double costCny,
                                  Double dailyBudgetCny, Double score) {
        var budget = dailyBudgetCny != null ? dailyBudgetCny : this.dailyBudgetCny;
        Boolean gate = score == null ? gatePassed : Boolean.valueOf(score >= 0.85);
        return new AgentSummary(name, displayName, category, language, runtime, template, env, version, status,
                statusNote, gate, ownerOrg, ownerUser, calls24h, callsTotal, p95Seconds, costCny,
                budget, score, instances, multiAgent, delegateCount);
    }

    private static String statusNote(AgentStatus status, String instances) {
        if (status == AgentStatus.DRAFT) {
            return "名称已预占，未注册";
        }
        if (status == AgentStatus.OFFLINE && instances != null) {
            return instances + " 就绪";
        }
        return null;
    }

    private static List<String> delegates(JsonNode manifest) {
        var values = new ArrayList<String>();
        if (manifest == null) {
            return values;
        }
        manifest.path("spec").path("delegates").forEach(item -> {
            if (!item.asText("").isBlank()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private static Double budget(JsonNode manifest) {
        if (manifest == null) {
            return null;
        }
        var node = manifest.path("spec").path("models").path("budget").path("dailyCny");
        return node.isNumber() ? node.asDouble() : null;
    }

    private static String text(JsonNode manifest, String... path) {
        if (manifest == null) {
            return null;
        }
        JsonNode node = manifest;
        for (var field : path) {
            node = node.path(field);
        }
        var value = node.asText("");
        return value.isBlank() ? null : value;
    }
}
