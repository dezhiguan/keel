package com.keel.llm;

import com.keel.common.error.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;

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
    private final Map<String, Long> latency = new HashMap<>();
    private final RedisCommands<String, String> redis;
    private static final String POLICY = "keel-llm:route-policy:v1";

    public RoutePolicy() {
        this(null);
    }

    public RoutePolicy(String redisUri) {
        redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
        versions.add(new Version(1, "balanced"));
    }

    public Version current() {
        if (redis != null) {
            String value = redis.eval("if redis.call('LLEN',KEYS[1])==0 then redis.call('RPUSH',KEYS[1],'1|balanced') end return redis.call('LINDEX',KEYS[1],-1)",
                    ScriptOutputType.VALUE, new String[]{POLICY});
            return parse(value);
        }
        return versions.getLast();
    }

    public Version publish(String objective) {
        if (!"balanced".equals(objective) && !"cost".equals(objective) && !"latency".equals(objective)) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "objective 只能是 balanced、cost 或 latency");
        }
        if (redis != null) {
            String value = redis.eval("if redis.call('LLEN',KEYS[1])==0 then redis.call('RPUSH',KEYS[1],'1|balanced') end local old=redis.call('LINDEX',KEYS[1],-1) local n=tonumber(string.match(old,'^(%d+)|'))+1 local next=tostring(n)..'|'..ARGV[1] redis.call('RPUSH',KEYS[1],next) return next",
                    ScriptOutputType.VALUE, new String[]{POLICY}, objective);
            return parse(value);
        }
        var next = new Version(current().version() + 1, objective);
        versions.add(next);
        return next;
    }

    public Version rollback() {
        if (redis != null) {
            String value = redis.eval("if redis.call('LLEN',KEYS[1])<2 then return '' end redis.call('RPOP',KEYS[1]) return redis.call('LINDEX',KEYS[1],-1)",
                    ScriptOutputType.VALUE, new String[]{POLICY});
            if (value == null || value.isBlank()) throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "没有可回滚的上一版路由策略");
            return parse(value);
        }
        if (versions.size() < 2) {
            throw new Gateway.GatewayException(ErrorCode.SERVER_INVALID_PARAM, "没有可回滚的上一版路由策略");
        }
        versions.removeLast();
        return current();
    }

    public List<String> plan(Gateway.VirtualKey key, String requested, boolean embedding, ModelCatalog catalog) {
        String upstream = catalog.require(requested).upstream();
        boolean unavailable = open(upstream);
        if (embedding || !key.allowFallback() || key.fallback().isEmpty()) {
            return unavailable ? List.of() : List.of(requested);
        }
        var alternate = pick(key, requested, catalog);
        if (alternate == null) {
            return unavailable ? List.of() : List.of(requested);
        }
        if (unavailable) {
            return List.of(alternate);
        }
        return List.of(requested, alternate);
    }

    /** Claim a half-open probe only when an admitted request is about to reach the upstream. */
    public boolean allowAttempt(String upstream) {
        return !open(upstream) && claimProbe(upstream);
    }

    public void observe(String upstream, boolean failed) {
        observe(upstream, failed, -1);
    }

    public void observe(String upstream, boolean failed, long latencyMs) {
        if (upstream == null || upstream.isBlank()) {
            return;
        }
        if (!failed && latencyMs >= 0) {
            if (redis != null) redis.setex(latencyKey(upstream), 86_400, Long.toString(latencyMs));
            else latency.put(upstream, latencyMs);
        }
        if (redis != null) {
            redis.eval("local untilAt=tonumber(redis.call('HGET',KEYS[1],'openUntil') or '0') if ARGV[2]=='0' then redis.call('DEL',KEYS[1]) redis.call('DEL',KEYS[2]) return 0 end local failures=tonumber(redis.call('HGET',KEYS[1],'failures') or '0')+1 if failures>=2 or (untilAt>0 and untilAt<=tonumber(ARGV[1])) then redis.call('HSET',KEYS[1],'failures',0,'openUntil',tonumber(ARGV[1])+60000) else redis.call('HSET',KEYS[1],'failures',failures) end redis.call('EXPIRE',KEYS[1],86400) redis.call('DEL',KEYS[2]) return 1",
                    ScriptOutputType.INTEGER, new String[]{breakerKey(upstream), probeKey(upstream)},
                    Long.toString(System.currentTimeMillis()), failed ? "1" : "0");
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
        if (redis != null) {
            long until = number(redis.hget(breakerKey(upstream), "openUntil"));
            return until > System.currentTimeMillis() || (until > 0 && redis.exists(probeKey(upstream)) > 0);
        }
        var breaker = channels.get(upstream);
        return breaker != null && breaker.openUntil > System.currentTimeMillis();
    }

    private boolean claimProbe(String upstream) {
        if (redis == null) return true;
        long until = number(redis.hget(breakerKey(upstream), "openUntil"));
        if (until == 0 || until > System.currentTimeMillis()) return true;
        return "OK".equals(redis.set(probeKey(upstream), "1", SetArgs.Builder.nx().px(300_000)));
    }

    private static long number(String value) { return value == null ? 0 : Long.parseLong(value); }
    private static String breakerKey(String upstream) { return "keel-llm:breaker:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(upstream.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String probeKey(String upstream) { return breakerKey(upstream) + ":probe"; }
    private static String latencyKey(String upstream) { return breakerKey(upstream) + ":latency"; }
    private static Version parse(String value) {
        var split = value.split("\\|", 2);
        return new Version(Integer.parseInt(split[0]), split[1]);
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
            return pool.stream().min(Comparator.comparingLong((String model) -> latencyOf(catalog.require(model).upstream()))
                    .thenComparing(model -> model)).orElseThrow();
        }
        return pool.getFirst();
    }

    private long latencyOf(String upstream) {
        if (redis != null) {
            var observed = redis.get(latencyKey(upstream));
            return observed == null ? Long.MAX_VALUE : Long.parseLong(observed);
        }
        return latency.getOrDefault(upstream, Long.MAX_VALUE);
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
