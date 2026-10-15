package com.keel.llm;

import com.keel.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One published routing objective. Rollback drops the latest version. */
public final class RoutePolicy {
    private final List<Version> versions = new ArrayList<>();
    private final Map<String, Breaker> channels = new HashMap<>();

    public RoutePolicy() {
        versions.add(new Version(1, "balanced"));
    }

    public Version current() {
        return versions.getLast();
    }

    public Version publish(String objective) {
        if (!"balanced".equals(objective) && !"cost".equals(objective) && !"latency".equals(objective)) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "objective 只能是 balanced、cost 或 latency");
        }
        var next = new Version(current().version() + 1, objective);
        versions.add(next);
        return next;
    }

    public Version rollback() {
        if (versions.size() < 2) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "没有可回滚的上一版路由策略");
        }
        versions.removeLast();
        return current();
    }

    public List<String> plan(Gateway.VirtualKey key, String requested, boolean embedding, ModelCatalog catalog) {
        if (embedding || !key.allowFallback() || key.fallback().isEmpty()) {
            return List.of(requested);
        }
        var alternate = pick(key, requested, catalog);
        if (alternate == null) {
            return List.of(requested);
        }
        if (open(catalog.require(requested).upstream())) {
            return List.of(alternate);
        }
        return List.of(requested, alternate);
    }

    public void observe(String upstream, boolean failed) {
        if (upstream == null || upstream.isBlank()) {
            return;
        }
        var breaker = channels.computeIfAbsent(upstream, ignored -> new Breaker());
        long now = System.currentTimeMillis();
        if (breaker.openUntil > now) {
            return;
        }
        if (!failed) {
            breaker.failures = 0;
            breaker.openUntil = 0;
            return;
        }
        breaker.failures++;
        if (breaker.failures >= 2) {
            breaker.openUntil = now + 60_000;
            breaker.failures = 0;
        }
    }

    public boolean open(String upstream) {
        var breaker = channels.get(upstream);
        return breaker != null && breaker.openUntil > System.currentTimeMillis();
    }

    private String pick(Gateway.VirtualKey key, String requested, ModelCatalog catalog) {
        var pool = key.fallback().stream()
                .filter(model -> !model.equals(requested) && key.models().contains(model))
                .filter(model -> !open(catalog.require(model).upstream()))
                .toList();
        if (pool.isEmpty()) {
            return null;
        }
        var objective = current().objective();
        if ("cost".equals(objective)) {
            return pool.stream().min(Comparator.comparing((String model) -> price(catalog, model)).thenComparing(model -> model)).orElseThrow();
        }
        if ("latency".equals(objective)) {
            return pool.stream().min(Comparator.comparing(String::toString)).orElseThrow();
        }
        return pool.getFirst();
    }

    private static BigDecimal price(ModelCatalog catalog, String model) {
        var priced = catalog.require(model);
        return priced.inputCnyPerToken().add(priced.outputCnyPerToken());
    }

    public record Version(int version, String objective) {}

    private static final class Breaker {
        private int failures;
        private long openUntil;
    }
}
