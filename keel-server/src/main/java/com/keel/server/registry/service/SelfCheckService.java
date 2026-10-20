package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.langfuse.LangfuseClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class SelfCheckService {
    private static final Set<String> EVENTS = Set.of("guardrail", "step", "tool", "token", "final", "error", "suspend");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final AgentEndpointClient endpoints;
    private final LangfuseClient langfuse;
    @Autowired
    private SelfCheckRecords records;
    @org.springframework.beans.factory.annotation.Value("${KEEL_META_AGENT_TOKEN:}")
    private String metaBridgeToken;

    public SelfCheckService() { this(null, null); }

    @Autowired
    public SelfCheckService(AgentEndpointClient endpoints, LangfuseClient langfuse) {
        this.endpoints = endpoints;
        this.langfuse = langfuse;
    }

    /** Probe the registered service, then look up its returned trace rather than hard coding failure. */
    public Report checkRegistered(String agent, String env, String endpoint, JsonNode manifest) {
        var report = checkRuntime(agent, env, endpoint, manifest);
        if (records != null) records.save(agent, env, manifest, report);
        return report;
    }

    /** A pre-registration preview probes the runtime without creating a registration record. */
    public Report checkRuntime(String agent, String env, String endpoint, JsonNode manifest) {
        var items = new ArrayList<Item>();
        var health = health(endpoint);
        items.add(health);
        String traceId = null;
        try {
            if (!health.passed()) throw new IllegalStateException("健康检查未通过");
            var bridgeToken = "meta-agent".equals(agent) ? metaBridgeToken : null;
            var actual = endpoints.manifest(endpoint, bridgeToken);
            if (!agent.equals(actual.path("metadata").path("name").asText())
                    || !manifest.path("spec").path("runtime").path("language").asText("python")
                    .equals(actual.path("spec").path("runtime").path("language").asText("python"))) {
                throw new IllegalStateException("运行服务的智能体名称或语言与登记配置不一致");
            }
            for (int probe = 0; probe < 5; probe++) {
                traceId = (bridgeToken == null || bridgeToken.isBlank()
                        ? endpoints.probe(endpoint, agent, env)
                        : endpoints.probe(endpoint, agent, env, bridgeToken)).traceId();
                if (traceId == null || traceId.isBlank()) throw new IllegalStateException("自检探针未返回 trace_id");
            }
            items.add(new Item("协议", true, traceId));
        } catch (Exception e) {
            items.add(new Item("协议", false, e.getMessage()));
        }
        boolean visible = false;
        if (traceId != null) {
            for (int attempt = 0; attempt < 12 && !visible; attempt++) {
                try {
                    // SDK batching plus Langfuse indexing can exceed six seconds.
                    if (attempt > 0) Thread.sleep(5000);
                    visible = !langfuse.observationsByTrace(traceId).path("data").isEmpty();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException e) {
                    // A transient cloud read error must not skip the remaining visibility window.
                }
            }
        }
        items.add(new Item("追踪", visible, visible ? traceId : traceId == null ? "协议自检未完成，未获得可核查的探针追踪" : "本次探针追踪尚未可见，请在链路追踪中复核"));
        boolean approval = approvalBound(manifest);
        items.add(new Item("审批绑定", approval, approval ? null : "高风险工具没有绑定审批策略"));
        var report = new Report(items.stream().allMatch(Item::passed), List.copyOf(items));
        return report;
    }

    public Report check(String endpoint, boolean traceVisible, boolean approvalBound) {
        var items = new ArrayList<Item>();
        items.add(health(endpoint));
        items.add(protocol(endpoint));
        items.add(new Item("追踪", traceVisible, traceVisible ? null : "Langfuse 里没有这次探针的 trace"));
        items.add(new Item("审批绑定", approvalBound, approvalBound ? null : "高风险工具没有绑定审批策略"));
        var passed = items.stream().allMatch(Item::passed);
        return new Report(passed, List.copyOf(items));
    }

    private Item health(String endpoint) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var ok = response.statusCode() == 200;
            return new Item("健康", ok, ok ? null : "HTTP " + response.statusCode());
        } catch (Exception e) {
            return new Item("健康", false, e.getClass().getSimpleName());
        }
    }

    private Item protocol(String endpoint) {
        try {
            for (var i = 0; i < 5; i++) {
                var response = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/invoke"))
                        .timeout(Duration.ofSeconds(3))
                        .header("X-Keel-Eval-Run", "self-check")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"text\":\"probe\"}}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || !knownEvent(response.body())) {
                    return new Item("协议", false, "第 " + (i + 1) + " 条探针不符合 sse-events");
                }
            }
            return new Item("协议", true, null);
        } catch (Exception e) {
            return new Item("协议", false, e.getClass().getSimpleName());
        }
    }

    static boolean knownEvent(String body) {
        var seen = false;
        for (var line : body.split("\n")) {
            if (line.startsWith("event:")) {
                seen = true;
                var name = line.substring("event:".length()).trim();
                if (!EVENTS.contains(name)) {
                    return false;
                }
            }
        }
        return seen;
    }

    public boolean approvalBound(JsonNode manifest) {
        for (var tool : manifest.path("spec").path("tools")) {
            if ("high".equals(tool.path("risk").asText()) && !"required".equals(tool.path("approval").asText())) {
                return false;
            }
        }
        return true;
    }

    public record Item(String name, boolean passed, String detail) {}
    public record Report(boolean passed, List<Item> items) {}
}
