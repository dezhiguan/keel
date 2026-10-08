package com.keel.server.insight;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.authgw.AuthGatewayClient;
import com.keel.server.integration.k8s.ServiceHealthProbe;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.prometheus.PrometheusExposition;
import com.keel.server.integration.ragforge.RagForgeInsightClient;
import com.keel.server.registry.service.AgentRegistryService;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Service
public class SharedServiceMonitor {
    private static final ExecutorService PROBES = Executors.newVirtualThreadPerTaskExecutor();
    private static final Duration RETRIEVAL_TTL = Duration.ofMinutes(3);
    private static final Duration SERVICES_TTL = Duration.ofSeconds(30);
    private static final Duration RECALL_TTL = Duration.ofMinutes(3);

    private final RagForgeInsightClient ragforge;
    private final LiteLlmClient gateway;
    private final LangfuseClient langfuse;
    private final AuthGatewayClient authGateway;
    private final KubernetesClient kubernetes;
    private final Function<String, List<String>> namesInEnv;
    private final AtomicBoolean retrievalRefresh = new AtomicBoolean();
    private List<LangfuseClient.Retrieval> retrievalCache;
    private Instant retrievalCachedAt;
    private final Map<String, CachedServices> servicesCache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedServices>> servicesInFlight = new ConcurrentHashMap<>();
    private final Set<String> refreshingServices = ConcurrentHashMap.newKeySet();
    private final Map<String, CachedRecall> recallCache = new ConcurrentHashMap<>();
    private final AtomicBoolean recallRefresh = new AtomicBoolean();

    private record CachedServices(Map<String, Object> value, Instant at) {}
    private record CachedRecall(JsonNode value, Instant at) {}

    public SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway) {
        this(ragforge, gateway, (Function<String, List<String>>) null, new LangfuseClient("", "", ""));
    }

    @Autowired
    public SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway, AgentRegistryService registry,
                                LangfuseClient langfuse, AuthGatewayClient authGateway, KubernetesClient kubernetes) {
        this(ragforge, gateway, registry == null ? null : registry::namesInEnv, langfuse, authGateway, kubernetes);
    }

    SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway, Function<String, List<String>> namesInEnv) {
        this(ragforge, gateway, namesInEnv, new LangfuseClient("", "", ""));
    }

    SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway, Function<String, List<String>> namesInEnv,
                         LangfuseClient langfuse) {
        this(ragforge, gateway, namesInEnv, langfuse, null, null);
    }

    SharedServiceMonitor(RagForgeInsightClient ragforge, LiteLlmClient gateway, Function<String, List<String>> namesInEnv,
                         LangfuseClient langfuse, AuthGatewayClient authGateway, KubernetesClient kubernetes) {
        this.ragforge = ragforge;
        this.gateway = gateway;
        this.namesInEnv = namesInEnv;
        this.langfuse = langfuse == null ? new LangfuseClient("", "", "") : langfuse;
        this.authGateway = authGateway;
        this.kubernetes = kubernetes;
    }

    public Map<String, Object> services() {
        return services("all");
    }

    public Map<String, Object> services(String env) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var cached = servicesCache.get(scope);
        if (cached != null) {
            if (cached.at().plus(SERVICES_TTL).isBefore(Instant.now()) && refreshingServices.add(scope)) {
                PROBES.execute(() -> {
                    try {
                        servicesCache.put(scope, new CachedServices(loadServices(scope), Instant.now()));
                    } catch (RuntimeException ignored) {
                        // Keep the last measured snapshot while an upstream is unavailable.
                    } finally {
                        refreshingServices.remove(scope);
                    }
                });
            }
            return cached.value();
        }
        var created = new CompletableFuture<CachedServices>();
        var existing = servicesInFlight.putIfAbsent(scope, created);
        if (existing != null) {
            return existing.join().value();
        }
        try {
            var result = new CachedServices(loadServices(scope), Instant.now());
            servicesCache.put(scope, result);
            created.complete(result);
            return result.value();
        } catch (RuntimeException e) {
            created.completeExceptionally(e);
            throw e;
        } finally {
            servicesInFlight.remove(scope, created);
        }
    }

    private Map<String, Object> loadServices(String scope) {
        Set<String> allowed = null;
        if (!"all".equals(scope) && namesInEnv != null) {
            allowed = new HashSet<>(namesInEnv.apply(scope));
        }
        var platform = PROBES.submit(() -> withPlatform(Map.of()));
        var insightRead = PROBES.submit(() -> ragforge.configured() ? ragforge.insight() : null);
        var scrapeRead = PROBES.submit(() -> ragforge.configured() ? ragforge.prometheus() : new RagForgeInsightClient.Scrape(List.of(), 0));
        var gatewayCostRead = PROBES.submit(() -> gatewayCost(scope));
        JsonNode insight = completed(insightRead, null);
        var scrape = completed(scrapeRead, new RagForgeInsightClient.Scrape(List.of(), 0));
        boolean up = ragforge.configured() && (insight != null || !scrape.bodies().isEmpty() || ragforge.up());
        var samples = new ArrayList<PrometheusExposition.Sample>();
        scrape.bodies().forEach(body -> samples.addAll(PrometheusExposition.parse(body)));

        var service = new LinkedHashMap<String, Object>();
        service.put("name", "rag-forge");
        service.put("role", "知识检索");
        service.put("instances", instances(scrape));
        Double p95Seconds = seconds(number(insight, "p95Ms"));
        Double throttle = PrometheusExposition.throttleRate(samples);
        service.put("p95", p95Seconds == null ? null : trim(p95Seconds) + "s");
        service.put("errorRate", throttle == null ? null : percent(throttle));
        service.put("status", status(up, scrape));

        var kpi = new LinkedHashMap<String, Object>();
        Long searches = insight == null ? null : longOrNull(insight.get("searches24h"));
        Long previous = insight == null ? null : longOrNull(insight.get("searchesPrev24h"));
        kpi.put("searches24h", searches);
        kpi.put("searchTrendPct", trend(searches, previous));
        kpi.put("p95Seconds", p95Seconds);
        kpi.put("p50Seconds", seconds(number(insight, "p50Ms")));
        kpi.put("throttleRate", throttle);
        var bases = insight == null ? List.<JsonNode>of() : children(insight.path("knowledgeBases"));
        kpi.put("kbCount", insight == null ? null : bases.size());
        kpi.put("staleKbCount", insight == null ? null : bases.stream().filter(row -> row.path("stale").asBoolean(false)).count());
        var gatewaySpent = completed(gatewayCostRead, null);
        kpi.put("modelCostCny", gatewaySpent != null ? gatewaySpent : "all".equals(scope) ? number(insight, "modelCostCny") : null);
        kpi.put("costSource", gatewaySpent != null ? "gateway" : "ragforge");

        var callers = PrometheusExposition.callers(samples);
        if (allowed != null) {
            var names = allowed;
            callers = callers.stream().filter(row -> names.contains(String.valueOf(row.get("agent")))).toList();
        }
        callers = new ArrayList<>(callers);
        var stages = new ArrayList<>(PrometheusExposition.stageMeans(samples));
        var knowledge = new ArrayList<Map<String, Object>>();
        bases.stream().map(SharedServiceMonitor::kb).forEach(knowledge::add);
        fillRecall(knowledge);
        fillFromLangfuse(kpi, service, stages, callers, allowed);
        callers = sortCallers(callers);

        var rag = new LinkedHashMap<String, Object>();
        rag.put("kpi", kpi);
        rag.put("stageLatency", stages);
        rag.put("callers", callers);
        rag.put("knowledgeBases", knowledge);

        var body = new LinkedHashMap<String, Object>();
        var serviceRows = new ArrayList<>(completed(platform, List.<Map<String, Object>>of()));
        if (serviceRows.size() == 7) {
            serviceRows.set(4, service);
        } else {
            serviceRows = new ArrayList<>(withPlatform(service));
        }
        body.put("services", serviceRows);
        body.put("ragforge", rag);
        body.put("consoleUrl", "https://ragforge.net");
        return body;
    }

    private static <T> T completed(java.util.concurrent.Future<T> future, T fallback) {
        try {
            return future.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private List<Map<String, Object>> withPlatform(Map<String, Object> ragforge) {
        var probe = new ServiceHealthProbe(kubernetes);
        var gatewayRow = PROBES.submit(() -> component("keel-gateway", "入口网关 · 自研轻量版",
                probe.cluster("keel-system", "keel-gateway", 8080, "/", 500)));
        var serverRow = PROBES.submit(() -> component("keel-server", "注册中心 · 工具 · 审批", keelServer(probe)));
        var auditRow = PROBES.submit(() -> component("keel-audit", "审计",
                probe.cluster("keel-system", "keel-audit", 8080, "/", 500)));
        var authRow = PROBES.submit(this::authRow);
        var llmRow = PROBES.submit(() -> llmRow(probe));
        var langfuseRow = PROBES.submit(this::langfuseRow);
        return List.of(
                join(gatewayRow, "keel-gateway", "入口网关 · 自研轻量版"),
                join(serverRow, "keel-server", "注册中心 · 工具 · 审批"),
                join(auditRow, "keel-audit", "审计"),
                join(authRow, "auth-gateway", "身份认证"),
                ragforge,
                join(llmRow, "薄网关", "模型网关"),
                join(langfuseRow, "Langfuse", "追踪 · 评测（Cloud 日本）"));
    }

    private ServiceHealthProbe.Result keelServer(ServiceHealthProbe probe) {
        var result = probe.cluster("keel-system", "keel-server", 8080, "/api/v1/catalog", 500);
        if (ServiceHealthProbe.inCluster() && result.instances() == null && !"ONLINE".equals(result.status())) {
            return new ServiceHealthProbe.Result(null, "ONLINE");
        }
        return result;
    }

    private Map<String, Object> authRow() {
        if (authGateway == null) {
            return component("auth-gateway", "身份认证", ServiceHealthProbe.Result.unknown());
        }
        var up = authGateway.jwksReachable();
        var status = up == null ? null : up ? "ONLINE" : "OFFLINE";
        return component("auth-gateway", "身份认证", new ServiceHealthProbe.Result(null, status));
    }

    private Map<String, Object> llmRow(ServiceHealthProbe probe) {
        var cluster = probe.cluster("keel-system", "keel-llm", 8088, "/health", 300);
        var http = gateway.healthy();
        if (cluster.status() != null) {
            if (Boolean.FALSE.equals(http) && "ONLINE".equals(cluster.status())) {
                return component("薄网关", "模型网关", new ServiceHealthProbe.Result(cluster.instances(), "DEGRADED"));
            }
            return component("薄网关", "模型网关", cluster);
        }
        var status = http == null ? null : http ? "ONLINE" : "OFFLINE";
        return component("薄网关", "模型网关", new ServiceHealthProbe.Result(null, status));
    }

    private Map<String, Object> langfuseRow() {
        if (!langfuse.configured()) {
            return component("Langfuse", "追踪 · 评测（Cloud 日本）", ServiceHealthProbe.Result.unknown());
        }
        var up = langfuse.healthy();
        var status = up == null ? null : up ? "ONLINE" : "OFFLINE";
        return component("Langfuse", "追踪 · 评测（Cloud 日本）", new ServiceHealthProbe.Result("云端", status));
    }

    private static Map<String, Object> join(java.util.concurrent.Future<Map<String, Object>> future, String name, String role) {
        try {
            return future.get();
        } catch (Exception e) {
            return component(name, role, ServiceHealthProbe.Result.unknown());
        }
    }

    private static Map<String, Object> component(String name, String role, ServiceHealthProbe.Result result) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("role", role);
        row.put("instances", result.instances());
        row.put("p95", null);
        row.put("errorRate", null);
        row.put("status", result.status());
        return row;
    }

    private void fillRecall(List<Map<String, Object>> knowledge) {
        var missing = new ArrayList<String>();
        var now = Instant.now();
        for (var row : knowledge) {
            if (row.get("recallAt5") != null) {
                continue;
            }
            var kb = String.valueOf(row.get("kb"));
            var cached = recallCache.get(kb);
            if (cached == null || cached.at().plus(RECALL_TTL).isBefore(now)) {
                missing.add(kb);
            }
            if (cached == null || cached.value() == null) {
                continue;
            }
            var recall = number(cached.value(), "recallAt5");
            if (recall != null) {
                row.put("recallAt5", recall);
            }
            if (row.get("zeroHitRate") == null) {
                var zero = number(cached.value(), "zeroResultRate");
                if (zero != null) {
                    row.put("zeroHitRate", zero);
                }
            }
        }
        if (!missing.isEmpty() && recallRefresh.compareAndSet(false, true)) {
            PROBES.execute(() -> {
                try {
                    for (var kb : missing) {
                        var summary = ragforge.evalSummary(kb);
                        if (summary == null) {
                            break;
                        }
                        recallCache.put(kb, new CachedRecall(summary, Instant.now()));
                    }
                } finally {
                    recallRefresh.set(false);
                }
            });
        }
    }

    /**
     * Retrieval P95 is a 24h aggregate. Serve a few minutes of cache, and keep the previous
     * sample on screen while a refresh runs, so opening the page does not wait on Langfuse.
     */
    private List<LangfuseClient.Retrieval> retrievals(Instant now) {
        synchronized (this) {
            if (retrievalCache != null && retrievalCachedAt != null && retrievalCachedAt.plus(RETRIEVAL_TTL).isAfter(now)) {
                return retrievalCache;
            }
            if (retrievalCache != null) {
                if (retrievalRefresh.compareAndSet(false, true)) {
                    PROBES.execute(() -> {
                        try {
                            storeRetrievals(langfuse.retrievals(Instant.now().minus(Duration.ofHours(24)), Instant.now()));
                        } finally {
                            retrievalRefresh.set(false);
                        }
                    });
                }
                return retrievalCache;
            }
        }
        var rows = langfuse.retrievals(now.minus(Duration.ofHours(24)), now);
        storeRetrievals(rows);
        return rows;
    }

    private void storeRetrievals(List<LangfuseClient.Retrieval> rows) {
        if (rows == null) {
            return;
        }
        synchronized (this) {
            retrievalCache = rows;
            retrievalCachedAt = Instant.now();
        }
    }

    private void fillFromLangfuse(Map<String, Object> kpi, Map<String, Object> service, List<Map<String, Object>> stages,
                                  List<Map<String, Object>> callers, Set<String> allowed) {
        var now = Instant.now();
        var rows = retrievals(now);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        var parents = new ArrayList<Double>();
        var byStage = new LinkedHashMap<String, List<Double>>();
        var byAgent = new LinkedHashMap<String, Integer>();
        for (var row : rows) {
            if (!row.stage().isBlank()) {
                if (row.latencySeconds() != null) {
                    byStage.computeIfAbsent(row.stage(), key -> new ArrayList<>()).add(row.latencySeconds());
                }
                continue;
            }
            if (row.latencySeconds() != null) {
                parents.add(row.latencySeconds());
            }
            if (!row.agent().isBlank() && (allowed == null || allowed.contains(row.agent()))) {
                byAgent.merge(row.agent(), 1, Integer::sum);
            }
        }
        if (kpi.get("p95Seconds") == null && !parents.isEmpty()) {
            var p95 = percentile(parents, 0.95);
            var p50 = percentile(parents, 0.50);
            kpi.put("p95Seconds", p95);
            if (kpi.get("p50Seconds") == null) {
                kpi.put("p50Seconds", p50);
            }
            service.put("p95", p95 == null ? null : trim(p95) + "s");
        }
        if (kpi.get("searches24h") instanceof Number count && count.longValue() == 0 && !parents.isEmpty()) {
            kpi.put("searches24h", (long) parents.size());
            kpi.put("searchTrendPct", null);
        }
        if (callers.isEmpty() && !byAgent.isEmpty()) {
            callers.clear();
            byAgent.forEach((agent, calls) -> callers.add(Map.of("agent", agent, "calls", calls)));
        }
        var present = new HashSet<String>();
        stages.forEach(stage -> present.add(String.valueOf(stage.get("stage"))));
        for (String stage : List.of("rewrite", "vector", "keyword", "rerank", "other")) {
            if (present.contains(stage) || !byStage.containsKey(stage)) {
                continue;
            }
            var p50 = percentile(byStage.get(stage), 0.50);
            if (p50 == null) {
                continue;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("stage", stage);
            row.put("p50Ms", (int) Math.round(p50 * 1000));
            row.put("basis", "p50");
            stages.add(row);
        }
        var order = List.of("rewrite", "vector", "keyword", "rerank", "other");
        stages.sort(Comparator.comparingInt(row -> {
            int index = order.indexOf(String.valueOf(row.get("stage")));
            return index < 0 ? order.size() : index;
        }));
    }

    private static List<Map<String, Object>> sortCallers(List<Map<String, Object>> callers) {
        var sorted = new ArrayList<>(callers);
        sorted.sort((left, right) -> Integer.compare(callsOf(right), callsOf(left)));
        return sorted;
    }

    private static int callsOf(Map<String, Object> row) {
        var value = row.get("calls");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Double percentile(List<Double> samples, double quantile) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        var sorted = new ArrayList<>(samples);
        sorted.sort(Double::compareTo);
        var index = (int) Math.ceil(sorted.size() * quantile) - 1;
        var value = sorted.get(Math.max(index, 0));
        return Math.round(value * 1000d) / 1000d;
    }

    private static String percent(double rate) {
        return String.format(Locale.US, "%.1f%%", rate * 100);
    }

    /** Present only when a rag-forge virtual key exists in this environment. */
    private Double gatewayCost(String env) {
        try {
            var keys = gateway.keys().stream()
                    .filter(key -> key.alias().startsWith("rag-forge-"))
                    .filter(key -> env == null || env.isBlank() || "all".equals(env) || env.equals(CostService.envOf(key.alias())))
                    .toList();
            if (keys.isEmpty()) {
                return null;
            }
            return keys.stream().mapToDouble(LiteLlmClient.VirtualKey::spentCny).sum();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String instances(RagForgeInsightClient.Scrape scrape) {
        if (scrape.targets() <= 0) {
            return null;
        }
        return scrape.bodies().size() + "/" + scrape.targets();
    }

    private static String status(boolean up, RagForgeInsightClient.Scrape scrape) {
        if (!up && scrape.targets() == 0 && scrape.bodies().isEmpty()) {
            return null;
        }
        if (scrape.targets() > 0 && scrape.bodies().size() < scrape.targets() && !scrape.bodies().isEmpty()) {
            return "DEGRADED";
        }
        return up ? "ONLINE" : "OFFLINE";
    }

    private static Double trend(Long current, Long previous) {
        if (current == null || previous == null) {
            return null;
        }
        if (previous == 0) {
            return current == 0 ? 0d : null;
        }
        return Math.round((current - previous) * 1000d / previous) / 10d;
    }

    private static Map<String, Object> kb(JsonNode row) {
        var kb = new LinkedHashMap<String, Object>();
        kb.put("kb", text(row, "kb"));
        kb.put("owner", text(row, "owner"));
        kb.put("documents", longOrNull(row.get("documents")));
        kb.put("updatedAt", text(row, "updatedAt"));
        kb.put("searches24h", longOrNull(row.get("searches24h")));
        kb.put("zeroHitRate", number(row, "zeroHitRate"));
        kb.put("recallAt5", number(row, "recallAt5"));
        kb.put("stale", row.path("stale").asBoolean(false));
        return kb;
    }

    private static List<JsonNode> children(JsonNode node) {
        var rows = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            node.forEach(rows::add);
        }
        return rows;
    }

    private static Double seconds(Double millis) {
        return millis == null ? null : Math.round(millis) / 1000d;
    }

    private static String trim(double seconds) {
        String text = Double.toString(seconds);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static Double number(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asDouble();
    }

    private static Long longOrNull(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.asLong();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
