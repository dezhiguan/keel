package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns Langfuse observations into the console trace list and detail.
 * Durations, tokens and cost come only from the observations and the configured unit prices.
 */
public final class TraceAssembly {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("careermate", "#2ec4b6"),
            Map.entry("askdb", "#5b9cf6"),
            Map.entry("offshore-wind", "#34c38f"),
            Map.entry("cs-bot", "#b48cf2"),
            Map.entry("ops-copilot", "#ff7a45"),
            Map.entry("prd-agent", "#f1b44c"),
            Map.entry("code-review", "#e36fae"),
            Map.entry("test-gen", "#6fd3e3"),
            Map.entry("ci-doctor", "#f46a6a"),
            Map.entry("dev-copilot", "#ffb08f"));
    private static final String[] PALETTE = {
            "#5b9cf6", "#2ec4b6", "#ff7a45", "#34c38f", "#b48cf2", "#e36fae", "#6fd3e3", "#ffb08f"
    };

    private TraceAssembly() {}

    static Map<String, Object> detail(String traceId, List<JsonNode> rows, String langfuseUrl,
                                      Map<String, double[]> prices) {
        var spans = spans(rows);
        if (spans.isEmpty()) {
            return null;
        }
        var nodes = new ArrayList<Map<String, Object>>();
        var edges = new ArrayList<Map<String, Object>>();
        for (Span span : spans) {
            nodes.add(span.node(prices));
            if (!span.parent.isBlank()) {
                var edge = new LinkedHashMap<String, Object>();
                edge.put("from", span.parent);
                edge.put("to", span.id);
                edge.put("atMs", span.start);
                edge.put("parallel", parallel(span, spans));
                edges.add(edge);
            }
        }
        var slices = slices(spans);
        int duration = slices.stream().mapToInt(Slice::ms).sum();
        var critical = new LinkedHashSet<String>();
        var breakdown = new ArrayList<Map<String, Object>>();
        for (Slice slice : slices) {
            critical.add(slice.span.id);
            var item = new LinkedHashMap<String, Object>();
            item.put("label", slice.span.label());
            item.put("ms", slice.ms());
            item.put("agentKey", slice.span.agentKey());
            breakdown.add(item);
        }
        for (var node : nodes) {
            node.put("criticalPath", critical.contains(node.get("id")));
        }
        var summary = summary(traceId, spans, duration);
        var spent = nodes.stream().map(node -> node.get("costCny")).filter(Number.class::isInstance)
                .mapToDouble(value -> ((Number) value).doubleValue()).sum();
        if (nodes.stream().anyMatch(node -> node.get("costCny") instanceof Number)) {
            summary.put("costCny", spent);
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("summary", summary);
        if (langfuseUrl != null && !langfuseUrl.isBlank()) {
            detail.put("langfuseUrl", langfuseUrl);
        }
        detail.put("agents", agents(spans, summary));
        detail.put("latencyBreakdown", breakdown);
        var costs = costBreakdown(nodes);
        if (!costs.isEmpty()) {
            detail.put("costBreakdown", costs);
        }
        detail.put("nodes", nodes);
        detail.put("edges", edges);
        return detail;
    }

    static Map<String, Object> summary(String traceId, List<JsonNode> rows, Map<String, double[]> prices) {
        var built = detail(traceId, rows, "", prices);
        if (built == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) built.get("summary");
        return summary;
    }

    static void overlay(Map<String, Object> summary, SavedTraces.TraceContext context) {
        if (context == null || context.empty()) {
            return;
        }
        put(summary, "pendingReason", context.pendingReason());
        put(summary, "runId", context.runId());
        if (context.suspendCount() != null && context.suspendCount() > 0) {
            summary.put("suspendCount", context.suspendCount());
        }
        put(summary, "userId", first(text(summary.get("userId")), context.userId()));
        put(summary, "sessionId", first(text(summary.get("sessionId")), context.sessionId()));
        if (context.humanWaitMs() != null && context.humanWaitMs() > 0 && summary.get("humanWaitMs") == null) {
            summary.put("humanWaitMs", context.humanWaitMs());
        }
    }

    public static void attach(Map<String, Object> detail, SavedTraces.TraceContext context) {
        if (detail == null || context == null || context.empty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) detail.get("summary");
        if (summary != null) {
            overlay(summary, context);
        }
        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) detail.get("nodes");
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        var target = nodes.getFirst();
        for (var node : nodes) {
            if (context.approvalId() != null && "tool".equals(node.get("type"))) {
                target = node;
                break;
            }
        }
        if (!context.auditIds().isEmpty()) {
            var ids = new ArrayList<String>();
            var existing = target.get("auditIds");
            if (existing instanceof List<?> list) {
                list.forEach(item -> ids.add(String.valueOf(item)));
            }
            for (var id : context.auditIds()) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            target.put("auditIds", ids);
        }
        if (context.approvalId() != null && target.get("approvalId") == null) {
            target.put("approvalId", context.approvalId());
        }
    }

    public static String pendingText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "input_required" -> "等待发起用户回复";
            case "handoff" -> "转人工";
            case "approval" -> "等待审批";
            case "" -> null;
            default -> reason;
        };
    }

    public static String color(String name) {
        if (name == null || name.isBlank()) {
            return "#8a97ab";
        }
        var known = COLORS.get(name);
        if (known != null) {
            return known;
        }
        return PALETTE[Math.floorMod(name.hashCode(), PALETTE.length)];
    }

    private static Map<String, Object> summary(String traceId, List<Span> spans, int duration) {
        var agents = agentNames(spans);
        var item = new LinkedHashMap<String, Object>();
        item.put("traceId", traceId);
        var question = question(spans);
        if (!question.isBlank()) {
            item.put("question", question);
        }
        var started = spans.stream().map(span -> span.startedAt).filter(value -> value != null).min(Comparator.naturalOrder());
        started.ifPresent(instant -> item.put("startedAt", instant.toString()));
        put(item, "sessionId", firstMeta(spans, "langfuse.session.id"));
        put(item, "userId", firstMeta(spans, "langfuse.user.id"));
        put(item, "env", env(spans));
        put(item, "runId", firstMeta(spans, "keel.run.id"));
        if (!agents.isEmpty()) {
            item.put("rootAgent", agents.getFirst());
            item.put("agents", agents);
        }
        item.put("multiAgent", agents.size() > 1 || spans.stream().anyMatch(span -> !span.parentAgent.isBlank()));
        item.put("durationMs", duration);
        int wait = spans.stream().mapToInt(span -> span.waitMs).max().orElse(0);
        if (wait > 0) {
            item.put("humanWaitMs", wait);
        }
        int tokens = spans.stream().mapToInt(span -> span.tokens).sum();
        if (tokens > 0) {
            item.put("tokens", tokens);
        }
        item.put("status", status(spans));
        item.put("blocked", blocked(spans));
        item.put("cached", spans.stream().anyMatch(span -> span.name.equals("cache.hit") || span.name.endsWith(".cache.hit")));
        return item;
    }

    private static List<Map<String, Object>> agents(List<Span> spans, Map<String, Object> summary) {
        var names = agentNames(spans);
        var env = text(summary.get("env"));
        var multi = Boolean.TRUE.equals(summary.get("multiAgent"));
        var root = names.isEmpty() ? "" : names.getFirst();
        var list = new ArrayList<Map<String, Object>>();
        for (var name : names) {
            var delegated = spans.stream().anyMatch(span -> name.equals(span.agent) && !span.parentAgent.isBlank());
            var kind = delegated ? "sub" : "supervisor";
            var subtitle = delegated ? "子智能体 · 跨服务" : (env.isBlank() ? "单智能体" : env + " · 单智能体");
            if (multi && name.equals(root) && !delegated) {
                subtitle = env.isBlank() ? "supervisor" : env + " · supervisor";
            }
            list.add(agent(name, name, subtitle, color(name), kind));
        }
        if (spans.stream().anyMatch(span -> span.waitMs > 0 && span.duration == 0)) {
            list.add(agent("human", "人工", "审批中心", "#f1b44c", "human"));
        }
        if (list.isEmpty()) {
            var name = spans.getFirst().name.isBlank() ? "keel" : spans.getFirst().name;
            list.add(agent(name, name, "单智能体", color(name), "supervisor"));
        }
        return list;
    }

    private static Map<String, Object> agent(String key, String name, String subtitle, String color, String kind) {
        var agent = new LinkedHashMap<String, Object>();
        agent.put("key", key);
        agent.put("name", name);
        agent.put("subtitle", subtitle);
        agent.put("color", color);
        agent.put("kind", kind);
        return agent;
    }

    private static List<Map<String, Object>> costBreakdown(List<Map<String, Object>> nodes) {
        var sums = new LinkedHashMap<String, Double>();
        for (var node : nodes) {
            if (!(node.get("costCny") instanceof Number cost)) {
                continue;
            }
            var key = text(node.get("agentKey"));
            if (key.isBlank()) {
                continue;
            }
            sums.merge(key, cost.doubleValue(), Double::sum);
        }
        var list = new ArrayList<Map<String, Object>>();
        sums.forEach((key, cost) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("agentKey", key);
            item.put("costCny", cost);
            list.add(item);
        });
        return list;
    }

    private static List<Span> spans(List<JsonNode> rows) {
        long origin = rows.stream()
                .map(TraceAssembly::startInstant)
                .filter(value -> value != null)
                .mapToLong(Instant::toEpochMilli)
                .min()
                .orElse(0);
        var spans = new ArrayList<Span>();
        for (JsonNode row : rows) {
            var started = startInstant(row);
            if (started == null) {
                continue;
            }
            spans.add(new Span(row, (int) Math.max(0, started.toEpochMilli() - origin)));
        }
        var children = new LinkedHashMap<String, Integer>();
        for (Span span : spans) {
            if (!span.parent.isBlank()) {
                children.merge(span.parent, 1, Integer::sum);
            }
        }
        for (Span span : spans) {
            span.depth = depth(span, spans, new LinkedHashSet<>());
            span.aggregated = "agent".equals(span.type) && children.getOrDefault(span.id, 0) > 0;
        }
        for (int pass = 0; pass < spans.size(); pass++) {
            var changed = false;
            for (Span span : spans) {
                changed |= span.inherit(spans);
            }
            if (!changed) {
                break;
            }
        }
        return spans;
    }

    private static int depth(Span span, List<Span> spans, Set<String> seen) {
        if (span.parent.isBlank() || !seen.add(span.id)) {
            return 0;
        }
        for (Span other : spans) {
            if (other.id.equals(span.parent)) {
                return depth(other, spans, seen) + 1;
            }
        }
        return 0;
    }

    private static boolean parallel(Span span, List<Span> spans) {
        int end = span.start + span.duration;
        for (Span other : spans) {
            if (other.id.equals(span.id) || !other.parent.equals(span.parent)) {
                continue;
            }
            int otherEnd = other.start + other.duration;
            if (span.start < otherEnd && other.start < end) {
                return true;
            }
        }
        return false;
    }

    private static List<Slice> slices(List<Span> spans) {
        var active = spans.stream().filter(span -> !(span.waitMs > 0 && span.duration == 0)).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        var cuts = new ArrayList<Integer>();
        for (Span span : active) {
            cuts.add(span.start);
            cuts.add(span.start + Math.max(span.duration, 0));
        }
        cuts.sort(Integer::compareTo);
        var unique = new ArrayList<Integer>();
        for (int cut : cuts) {
            if (unique.isEmpty() || unique.getLast() != cut) {
                unique.add(cut);
            }
        }
        var raw = new ArrayList<Slice>();
        for (int i = 0; i < unique.size() - 1; i++) {
            int from = unique.get(i);
            int to = unique.get(i + 1);
            if (to <= from) {
                continue;
            }
            Span owner = null;
            for (Span span : active) {
                if (span.start <= from && span.start + span.duration >= to
                        && (owner == null || span.depth > owner.depth
                        || (span.depth == owner.depth && span.duration > owner.duration))) {
                    owner = span;
                }
            }
            if (owner != null) {
                raw.add(new Slice(owner, to - from));
            }
        }
        var merged = new ArrayList<Slice>();
        for (Slice slice : raw) {
            if (!merged.isEmpty() && merged.getLast().span == slice.span) {
                var last = merged.removeLast();
                merged.add(new Slice(last.span, last.ms + slice.ms));
            } else {
                merged.add(slice);
            }
        }
        return merged;
    }

    private static int wall(List<Span> spans) {
        return slices(spans).stream().mapToInt(Slice::ms).sum();
    }

    private static List<String> agentNames(List<Span> spans) {
        var names = new ArrayList<String>();
        for (Span span : spans) {
            if (!span.agent.isBlank() && !names.contains(span.agent)) {
                names.add(span.agent);
            }
        }
        return names;
    }

    private static String question(List<Span> spans) {
        for (Span span : spans) {
            if (span.parent.isBlank() && !span.input.isBlank()) {
                return span.input;
            }
        }
        for (Span span : spans) {
            if (!span.input.isBlank()) {
                return span.input;
            }
        }
        return "";
    }

    private static String env(List<Span> spans) {
        var explicit = normalizeEnv(firstMeta(spans, "keel.env"));
        if (!explicit.isBlank()) {
            return explicit;
        }
        return CostService.envOf(firstMeta(spans, "keel.llm.key_alias"));
    }

    /** Langfuse writes `production`; the console environment name is `prod`. Anything else is not an environment. */
    private static String normalizeEnv(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw.trim()) {
            case "dev", "test", "staging", "prod" -> raw.trim();
            case "production" -> "prod";
            default -> "";
        };
    }

    private static String status(List<Span> spans) {
        var rank = 0;
        var status = "ok";
        for (Span span : spans) {
            var next = switch (span.status) {
                case "failed" -> 2;
                case "fallback" -> 1;
                default -> 0;
            };
            if (next > rank) {
                rank = next;
                status = next == 2 ? "failed" : "fallback";
            }
        }
        return status;
    }

    private static boolean blocked(List<Span> spans) {
        for (Span span : spans) {
            if (span.blocked) {
                return true;
            }
            if ("failed".equals(span.status) && ("guardrail".equals(span.type) || span.name.startsWith("guard.")
                    || "gateway.auth".equals(span.name))) {
                return true;
            }
        }
        return false;
    }

    private static String firstMeta(List<Span> spans, String key) {
        for (Span span : spans) {
            var value = span.meta.getOrDefault(key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Instant startInstant(JsonNode row) {
        var text = row.path("startTime").asText("");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static String first(String left, String right) {
        return left == null || left.isBlank() ? (right == null ? "" : right) : left;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private record Slice(Span span, int ms) {}

    private static final class Span {
        final String id;
        final String parent;
        final String name;
        final String type;
        String agent;
        final String parentAgent;
        final String status;
        final String input;
        final String output;
        final String model;
        final String fallbackFrom;
        final String keyAlias;
        final Instant startedAt;
        final int start;
        final int duration;
        final int waitMs;
        final int tokensIn;
        final int tokensOut;
        final int tokens;
        final boolean blocked;
        final Map<String, String> meta;
        final List<String> auditIds;
        int depth;
        boolean aggregated;

        Span(JsonNode row, int start) {
            this.id = row.path("id").asText();
            this.parent = row.path("parentObservationId").asText("");
            this.name = row.path("name").asText("");
            this.meta = meta(row);
            this.type = observationType(row, meta);
            this.agent = meta.getOrDefault("keel.agent", "");
            this.parentAgent = meta.getOrDefault("keel.parent_agent", "");
            this.status = nodeStatus(row, meta);
            this.input = observationText(row, "input");
            this.output = observationText(row, "output");
            this.model = model(row, meta);
            this.fallbackFrom = meta.getOrDefault("keel.fallback_from", "");
            this.keyAlias = meta.getOrDefault("keel.llm.key_alias", "");
            this.startedAt = startInstant(row);
            this.start = start;
            this.duration = duration(row, startedAt);
            this.waitMs = number(meta.get("keel.run.wait_ms"));
            this.tokensIn = tokens(row, meta, "gen_ai.usage.input_tokens", "input");
            this.tokensOut = tokens(row, meta, "gen_ai.usage.output_tokens", "output");
            this.tokens = tokensIn + tokensOut;
            this.blocked = "true".equalsIgnoreCase(meta.getOrDefault("keel.blocked", ""));
            this.auditIds = auditIds(row);
        }

        String label() {
            return name.isBlank() ? type : name;
        }

        boolean inherit(List<Span> spans) {
            if (!agent.isBlank() || parent.isBlank()) {
                return false;
            }
            for (Span other : spans) {
                if (other.id.equals(parent) && !other.agent.isBlank()) {
                    agent = other.agent;
                    return true;
                }
            }
            return false;
        }

        String agentKey() {
            if (!agent.isBlank()) {
                return agent;
            }
            return waitMs > 0 && duration == 0 ? "human" : name;
        }

        Map<String, Object> node(Map<String, double[]> prices) {
            var node = new LinkedHashMap<String, Object>();
            node.put("id", id);
            node.put("agentKey", agentKey());
            node.put("type", type);
            node.put("name", name.isBlank() ? type : name);
            if (!agent.isBlank()) {
                node.put("service", agent);
            }
            node.put("startMs", start);
            node.put("durationMs", duration);
            if (waitMs > 0) {
                node.put("humanWaitLabel", (waitMs / 60_000) + "m" + ((waitMs % 60_000) / 1000) + "s");
            }
            node.put("status", status.isBlank() ? "ok" : status);
            node.put("depth", depth);
            node.put("aggregated", aggregated);
            if (!model.isBlank()) {
                node.put("model", model);
            }
            if (!fallbackFrom.isBlank()) {
                node.put("fallbackFrom", fallbackFrom);
            }
            if (!keyAlias.isBlank()) {
                node.put("llmKeyAlias", keyAlias);
            }
            if (tokens > 0) {
                node.put("tokensIn", tokensIn);
                node.put("tokensOut", tokensOut);
                node.put("tokens", tokens);
            }
            node.put("costCny", cost(prices));
            node.put("inputSummary", input.isBlank() ? label() : input);
            node.put("outputSummary", output.isBlank() ? label() : output);
            node.put("auditIds", auditIds);
            var promptName = meta.getOrDefault("langfuse.observation.prompt.name", "");
            var promptVersion = meta.getOrDefault("langfuse.observation.prompt.version", "");
            if (!promptName.isBlank()) {
                node.put("promptName", promptName);
            }
            if (!promptVersion.isBlank()) {
                try {
                    node.put("promptVersion", Integer.valueOf(promptVersion));
                } catch (NumberFormatException ignored) {
                    // A non-integer version is not a Langfuse prompt version.
                }
            }
            if ("true".equalsIgnoreCase(meta.getOrDefault("keel.prompt.fallback", ""))) {
                node.put("promptFallback", true);
            }
            return node;
        }

        private Double cost(Map<String, double[]> prices) {
            if (model.isBlank() || tokens == 0 || prices == null) {
                return null;
            }
            var price = prices.get(model);
            if (price == null) {
                return null;
            }
            return tokensIn * price[0] + tokensOut * price[1];
        }
    }

    private static Map<String, String> meta(JsonNode row) {
        var values = new LinkedHashMap<String, String>();
        var metadata = row.path("metadata");
        if (metadata.isObject()) {
            absorb(values, metadata);
            absorb(values, metadata.path("attributes"));
            absorb(values, metadata.path("resourceAttributes"));
        }
        putIfBlank(values, "langfuse.user.id", row.path("userId").asText(""));
        putIfBlank(values, "langfuse.session.id", row.path("sessionId").asText(""));
        putIfBlank(values, "keel.env", normalizeEnv(row.path("environment").asText("")));
        return values;
    }

    /** Top-level metadata wins. OTEL attributes that Langfuse did not promote sit under metadata.attributes. */
    private static void absorb(Map<String, String> values, JsonNode object) {
        if (object == null || !object.isObject()) {
            return;
        }
        object.properties().forEach(entry -> {
            var key = entry.getKey().startsWith("attributes.")
                    ? entry.getKey().substring("attributes.".length()) : entry.getKey();
            if ("attributes".equals(key) || "resourceAttributes".equals(key)) {
                return;
            }
            var node = entry.getValue();
            if (node.isValueNode() && !node.isNull() && !node.asText("").isBlank()) {
                values.putIfAbsent(key, node.asText());
            }
        });
    }

    private static void putIfBlank(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.putIfAbsent(key, value);
        }
    }

    private static String observationType(JsonNode row, Map<String, String> meta) {
        var named = meta.getOrDefault("langfuse.observation.type", "");
        if (!named.isBlank()) {
            return named.toLowerCase();
        }
        var type = row.path("type").asText("");
        if (type.isBlank()) {
            return "span";
        }
        return switch (type.toUpperCase()) {
            case "GENERATION" -> "generation";
            case "AGENT" -> "agent";
            case "TOOL" -> "tool";
            case "RETRIEVER" -> "retriever";
            case "GUARDRAIL" -> "guardrail";
            case "EVENT" -> "event";
            default -> type.toLowerCase();
        };
    }

    private static String nodeStatus(JsonNode row, Map<String, String> meta) {
        var status = meta.getOrDefault("keel.status", "");
        if (status.equals("ok") || status.equals("fallback") || status.equals("failed")) {
            return status;
        }
        return "ERROR".equalsIgnoreCase(row.path("level").asText("")) ? "failed" : "ok";
    }

    private static String model(JsonNode row, Map<String, String> meta) {
        var direct = row.path("model").asText("");
        if (!direct.isBlank()) {
            return direct;
        }
        var named = meta.getOrDefault("gen_ai.request.model", "");
        return named;
    }

    private static int duration(JsonNode row, Instant started) {
        var end = row.path("endTime").asText("");
        if (started == null || end.isBlank()) {
            return 0;
        }
        try {
            return (int) Math.max(0, Instant.parse(end).toEpochMilli() - started.toEpochMilli());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int tokens(JsonNode row, Map<String, String> meta, String attribute, String usageField) {
        var fromMeta = number(meta.get(attribute));
        if (fromMeta > 0) {
            return fromMeta;
        }
        var named = "input".equals(usageField) ? row.path("inputUsage") : row.path("outputUsage");
        if (named.isNumber() && named.asInt(0) > 0) {
            return named.asInt();
        }
        var usage = row.path("usage").path(usageField);
        if (usage.isNumber() && usage.asInt(0) > 0) {
            return usage.asInt();
        }
        var details = row.path("usageDetails");
        var keys = "input".equals(usageField)
                ? List.of("input", "input_tokens", "prompt_tokens")
                : List.of("output", "output_tokens", "completion_tokens");
        for (var key : keys) {
            var value = details.path(key);
            if (value.isNumber() && value.asInt(0) > 0) {
                return value.asInt();
            }
        }
        return 0;
    }

    private static int number(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> auditIds(JsonNode row) {
        var ids = new ArrayList<String>();
        var node = row.path("metadata").path("keel.audit_ids");
        if (node.isArray()) {
            node.forEach(item -> {
                if (!item.asText("").isBlank()) {
                    ids.add(item.asText());
                }
            });
        } else if (node.isTextual() && !node.asText().isBlank()) {
            ids.add(node.asText());
        }
        return ids;
    }

    private static String observationText(JsonNode row, String field) {
        var node = row.path(field);
        if (node.isMissingNode() || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            node = row.path("metadata").path("langfuse.observation." + field);
        }
        return textValue(node);
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            var text = node.asText().trim();
            if (text.startsWith("{") || text.startsWith("[")) {
                try {
                    var parsed = JSON.readTree(text);
                    if (parsed.isObject() || parsed.isArray()) {
                        var inner = textValue(parsed);
                        if (!inner.isBlank()) {
                            return inner;
                        }
                    }
                } catch (java.io.IOException ignored) {
                    // The observation input is ordinary text that happens to start with a bracket.
                }
            }
            return text;
        }
        if (node.isArray()) {
            return userMessage(node);
        }
        if (node.isObject()) {
            for (var key : List.of("text", "question", "query", "input")) {
                var extracted = textValue(node.path(key));
                if (!extracted.isBlank()) {
                    return extracted;
                }
            }
            var messages = node.path("messages");
            if (messages.isArray()) {
                var user = userMessage(messages);
                if (!user.isBlank()) {
                    return user;
                }
            }
        }
        return "";
    }

    /** The list shows the user's question, which is the last user turn inside a chat payload. */
    private static String userMessage(JsonNode messages) {
        var lastUser = "";
        var last = "";
        for (var item : messages) {
            var content = item.isTextual() ? item.asText().trim() : contentOf(item);
            if (content.isBlank()) {
                continue;
            }
            last = content;
            var role = item.path("role").asText("");
            if (role.isBlank() || "user".equalsIgnoreCase(role) || "human".equalsIgnoreCase(role)) {
                lastUser = content;
            }
        }
        return lastUser.isBlank() ? last : lastUser;
    }

    private static String contentOf(JsonNode item) {
        var content = item.path("content");
        if (content.isTextual()) {
            return content.asText().trim();
        }
        if (content.isArray()) {
            var parts = new StringBuilder();
            for (var part : content) {
                var text = part.isTextual() ? part.asText() : part.path("text").asText("");
                if (!text.isBlank()) {
                    if (!parts.isEmpty()) {
                        parts.append('\n');
                    }
                    parts.append(text.trim());
                }
            }
            return parts.toString();
        }
        if (content.isObject()) {
            return content.path("text").asText("").trim();
        }
        return "";
    }
}
