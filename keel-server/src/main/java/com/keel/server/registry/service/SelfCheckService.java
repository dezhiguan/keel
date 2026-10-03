package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

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
    private static final Set<String> EVENTS = Set.of("step", "tool", "token", "final", "error", "suspend");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

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
