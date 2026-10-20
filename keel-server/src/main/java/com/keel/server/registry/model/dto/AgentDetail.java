package com.keel.server.registry.model.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.memory.MemoryLayers;
import com.keel.server.registry.model.AgentCategories;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentInstance;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;

import java.util.ArrayList;
import java.util.List;

/** Detail drawer. Findings stay empty until reconcile is shown here. */
public record AgentDetail(
        @JsonUnwrapped AgentSummary summary,
        String manifestYaml,
        String manifestHash,
        Execution execution,
        Memory memory,
        List<String> knowledgeBases,
        List<String> tools,
        List<String> models,
        List<String> delegates,
        CheckReport selfCheck,
        AgentElements elements,
        List<InstanceRow> instanceList,
        List<ResourceRow> resources,
        List<VersionRow> versions,
        List<Object> findings) {

    public AgentDetail withElements(AgentElements next) {
        return new AgentDetail(summary, manifestYaml, manifestHash, execution, memory, knowledgeBases, tools, models,
                delegates, selfCheck, next, instanceList, resources, versions, findings);
    }

    public static AgentDetail of(Agent agent, AgentVersion latest, String instances,
                                 List<AgentInstance> rows, JsonNode manifest, List<ResourceRow> resources) {
        var summary = AgentSummary.of(agent, latest, instances, manifest);
        var bound = resources == null ? List.<ResourceRow>of() : List.copyOf(resources);
        var instanceList = rows.stream().map(InstanceRow::of).toList();
        var modelNames = models(manifest);
        return new AgentDetail(summary, ManifestYaml.of(manifest), latest == null ? null : latest.getManifestHash(),
                execution(manifest),
                memoryStatus(agent, manifest, 0, 0, 0),
                texts(manifest, "knowledge", "kb"),
                toolLabels(manifest),
                modelNames,
                delegates(manifest),
                checks(summary, modelNames, manifest, bound, instanceList),
                AgentElements.of(manifest),
                instanceList,
                bound, List.of(), List.of());
    }

    public AgentDetail withVersions(List<VersionRow> versions) {
        return new AgentDetail(summary, manifestYaml, manifestHash, execution, memory, knowledgeBases, tools, models, delegates,
                selfCheck, elements, instanceList, resources, versions == null ? List.of() : List.copyOf(versions), findings);
    }

    private static CheckReport checks(AgentSummary summary, List<String> models, JsonNode manifest,
                                      List<ResourceRow> resources, List<InstanceRow> instances) {
        if (summary.status() == AgentStatus.DRAFT) {
            return CheckReport.unknown();
        }
        var items = new ArrayList<CheckItem>();
        var apiVersion = manifest == null ? "" : manifest.path("apiVersion").asText("");
        items.add(new CheckItem("agent.yaml 校验通过", "keel/v1".equals(apiVersion),
                "keel/v1".equals(apiVersion) ? "keel/v1" : (apiVersion.isBlank() ? "缺失" : apiVersion)));
        items.add(keyCheck(models, summary.dailyBudgetCny(), resources));
        items.add(secretCheck(resources));
        items.add(healthCheck(instances));
        items.add(new CheckItem("调用协议自检", null, "未探测"));
        items.add(new CheckItem("追踪上报正常", null, "未探测"));
        items.add(approvalCheck(manifest));
        items.add(new CheckItem("评测门禁", null, "未运行"));
        return CheckReport.of(items);
    }

    private static CheckItem keyCheck(List<String> models, Double budget, List<ResourceRow> resources) {
        var row = find(resources, "litellm_key");
        if (row == null) {
            return new CheckItem("薄网关 虚拟 Key 已发放", false, "未创建");
        }
        if (!"ACTIVE".equalsIgnoreCase(row.status())) {
            return new CheckItem("薄网关 虚拟 Key 已发放", false, revoked(row.status(), "已吊销"));
        }
        var detail = models.size() + " 个模型";
        if (budget != null) {
            var text = budget == Math.rint(budget) ? String.valueOf(budget.longValue()) : String.valueOf(budget);
            detail = detail + " · ¥" + text + "/天";
        }
        return new CheckItem("薄网关 虚拟 Key 已发放", true, detail);
    }

    private static CheckItem secretCheck(List<ResourceRow> resources) {
        var row = find(resources, "secret");
        if (row == null) {
            return new CheckItem("Langfuse Key 已写入 Secret", false, "未创建");
        }
        if (!"ACTIVE".equalsIgnoreCase(row.status())) {
            return new CheckItem("Langfuse Key 已写入 Secret", false, revoked(row.status(), "已删除"));
        }
        return new CheckItem("Langfuse Key 已写入 Secret", true, row.externalId());
    }

    private static CheckItem healthCheck(List<InstanceRow> instances) {
        if (instances.isEmpty()) {
            return new CheckItem("/v1/health 返回 200", false, "尚未部署");
        }
        var ready = instances.stream().anyMatch(InstanceRow::ready);
        if (!ready) {
            return new CheckItem("/v1/health 返回 200", false, "没有就绪实例");
        }
        return new CheckItem("/v1/health 返回 200", null, "未探测");
    }

    private static CheckItem approvalCheck(JsonNode manifest) {
        var high = new ArrayList<String>();
        var unbound = new ArrayList<String>();
        if (manifest != null) {
            for (var tool : manifest.path("spec").path("tools")) {
                if (!"high".equals(tool.path("risk").asText())) {
                    continue;
                }
                var name = tool.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                high.add(name);
                if (!"required".equals(tool.path("approval").asText())) {
                    unbound.add(name);
                }
            }
        }
        if (high.isEmpty()) {
            return new CheckItem("高风险工具已绑定审批", true, "无高风险工具");
        }
        if (unbound.isEmpty()) {
            return new CheckItem("高风险工具已绑定审批", true, String.join("、", high));
        }
        return new CheckItem("高风险工具已绑定审批", false, String.join("、", unbound));
    }

    private static ResourceRow find(List<ResourceRow> resources, String type) {
        for (var row : resources) {
            if (type.equals(row.type())) {
                return row;
            }
        }
        return null;
    }

    private static String revoked(String status, String revokedLabel) {
        return "REVOKED".equalsIgnoreCase(status) ? revokedLabel : status;
    }

    private static List<String> texts(JsonNode manifest, String array, String field) {
        if (manifest == null) {
            return List.of();
        }
        var spec = manifest.path("spec").path(array);
        if (!spec.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        spec.forEach(item -> {
            var value = item.path(field).asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private static List<String> toolLabels(JsonNode manifest) {
        if (manifest == null) {
            return List.of();
        }
        var spec = manifest.path("spec").path("tools");
        if (!spec.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        spec.forEach(item -> {
            var name = item.path("name").asText("");
            if (name.isBlank()) {
                return;
            }
            var access = switch (item.path("access").asText("read")) {
                case "write" -> "写";
                case "exec" -> "执行";
                default -> "读";
            };
            var risk = item.path("risk").asText("low");
            var approval = item.path("approval").asText("none");
            var riskText = switch (risk) {
                case "mid" -> "中";
                case "high" -> "required".equals(approval) ? "高 · 需审批" : "高";
                default -> "低";
            };
            values.add(name + " · " + access + " · " + riskText);
        });
        return values;
    }

    private static List<String> models(JsonNode manifest) {
        if (manifest == null) {
            return List.of();
        }
        var models = manifest.path("spec").path("models");
        var values = new ArrayList<String>();
        if (models.hasNonNull("default")) {
            values.add(models.get("default").asText());
        }
        models.path("fallback").forEach(item -> {
            if (!item.asText("").isBlank()) {
                values.add(item.asText());
            }
        });
        return values;
    }

    private static List<String> delegates(JsonNode manifest) {
        if (manifest == null) {
            return List.of();
        }
        var values = new ArrayList<String>();
        manifest.path("spec").path("delegates").forEach(item -> values.add(item.asText()));
        return values;
    }

    private static Execution execution(JsonNode manifest) {
        if (manifest == null) return null;
        var spec = manifest.path("spec");
        var kind = spec.path("kind").asText("");
        if ("agent".equals(kind)) {
            var steps = spec.path("models").path("budget").path("maxSteps").asInt(6);
            return new Execution("声明为智能体", "ctx.loop（运行时由 SDK 执行）",
                    "maxSteps " + steps + " · 运行预算 · 重复工具检测 · 高风险工具挂起审批");
        }
        if ("workflow".equals(kind)) {
            return new Execution("声明为工作流", "确定性代码流程", "工具权限与模型预算按 manifest 执行");
        }
        return new Execution("未声明", "未声明", "以 agent.yaml 为准");
    }

    public static Memory memoryStatus(Agent agent, JsonNode manifest, long userCount, long taskCount, long experienceCount) {
        if (agent == null || manifest == null) return null;
        var mode = manifest.path("spec").path("interaction").path("mode").asText("");
        var status = MemoryLayers.describe(agent.getLayer(), AgentCategories.resolve(agent.getName(), manifest),
                agent.getName(), mode, userCount, taskCount, experienceCount);
        return new Memory(status.session(), status.user(), status.task(), status.experience());
    }

    public AgentDetail withMemory(Memory next) {
        return new AgentDetail(summary, manifestYaml, manifestHash, execution, next, knowledgeBases, tools, models, delegates,
                selfCheck, elements, instanceList, resources, versions, findings);
    }

    public AgentDetail withSummary(AgentSummary summary) {
        return new AgentDetail(summary, manifestYaml, manifestHash, execution, memory, knowledgeBases, tools, models, delegates,
                selfCheck, elements, instanceList, resources, versions, findings);
    }

    public AgentDetail withSelfCheck(CheckReport report) {
        return new AgentDetail(summary, manifestYaml, manifestHash, execution, memory, knowledgeBases, tools, models, delegates,
                report, elements, instanceList, resources, versions, findings);
    }

    public record InstanceRow(String instanceId, String source, String version, boolean ready, String lastSeenAt) {
        static InstanceRow of(AgentInstance row) {
            return new InstanceRow(row.getInstanceId(), row.getSource(), row.getVersion(), row.isReady(),
                    row.getLastSeenAt() == null ? null : row.getLastSeenAt().toString());
        }
    }

    public record Execution(String current, String target, String constraints) {}

    public record Memory(String session, String user, String task, String experience) {}

    public record ResourceRow(String type, String externalId, String status) {}

    /** 版本页一行。评测分和门禁由 VersionEvidence 按该版本的评测或发布记录补上。 */
    public record VersionRow(String version, String env, Boolean gatePassed, Double scoreTotal,
                             String image, String ciRunUrl, String releasedBy, String releasedAt,
                             String state, List<String> publishedEnvs) {
        public static VersionRow of(AgentVersion row) {
            return new VersionRow(row.getVersion(), row.getEnv(), null, null, row.getImage(), null,
                    row.getReleasedBy(), row.getReleasedAt() == null ? null : row.getReleasedAt().toString(), null, List.of());
        }

        public VersionRow withEvaluation(Boolean gate, Double score) {
            if (gate == null && score == null) return this;
            return new VersionRow(version, env, gate != null ? gate : gatePassed, score != null ? score : scoreTotal,
                    image, ciRunUrl, releasedBy, releasedAt, state, publishedEnvs);
        }

        public VersionRow withLifecycle(String state, List<String> publishedEnvs) {
            return new VersionRow(version, env, gatePassed, scoreTotal, image, ciRunUrl, releasedBy, releasedAt,
                    state, List.copyOf(publishedEnvs));
        }
    }

    public record CheckItem(String name, Boolean passed, String detail) {}

    public record CheckReport(Boolean passed, List<CheckItem> items, String checkedAt) {
        public CheckReport(Boolean passed, List<CheckItem> items) { this(passed, items, null); }
        static CheckReport unknown() {
            return new CheckReport(null, List.of(
                    new CheckItem("agent.yaml 校验通过", null, null),
                    new CheckItem("薄网关 虚拟 Key 已发放", null, null),
                    new CheckItem("Langfuse Key 已写入 Secret", null, null),
                    new CheckItem("/v1/health 返回 200", null, null),
                    new CheckItem("调用协议自检", null, null),
                    new CheckItem("追踪上报正常", null, null),
                    new CheckItem("高风险工具已绑定审批", null, null),
                    new CheckItem("评测门禁", null, null)));
        }

        static CheckReport of(List<CheckItem> items) {
            var failed = items.stream().anyMatch(item -> Boolean.FALSE.equals(item.passed()));
            var unknown = items.stream().anyMatch(item -> item.passed() == null);
            Boolean passed = failed ? Boolean.FALSE : unknown ? null : Boolean.TRUE;
            return new CheckReport(passed, List.copyOf(items));
        }
    }
}
