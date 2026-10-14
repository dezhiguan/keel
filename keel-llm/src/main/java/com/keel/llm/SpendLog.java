package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Recent model calls, shared by every gateway replica. Redis keeps them across restarts. */
public final class SpendLog {
    static final String KEY = "keel-llm:spend";
    static final String PENDING_KEY = "keel-llm:pending-spend";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX = 5000;

    private final RedisCommands<String, String> redis;
    private final List<String> memory = new ArrayList<>();
    private final Map<String, String> pending = new HashMap<>();

    public SpendLog(String redisUri) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public void append(Gateway.Spend row) {
        var encoded = encode(row);
        if (redis != null) {
            if ("PENDING".equals(row.status())) redis.hset(PENDING_KEY, row.requestId(), encoded);
            else if ("SETTLED".equals(row.status())) redis.hdel(PENDING_KEY, row.requestId());
            redis.rpush(KEY, encoded);
            redis.ltrim(KEY, -MAX, -1);
            return;
        }
        synchronized (memory) {
            if ("PENDING".equals(row.status())) pending.put(row.requestId(), encoded);
            else if ("SETTLED".equals(row.status())) pending.remove(row.requestId());
            memory.add(encoded);
            if (memory.size() > MAX) {
                memory.remove(0);
            }
        }
    }

    public List<Gateway.Spend> all() {
        List<String> raw;
        List<String> pendingRaw;
        if (redis != null) {
            raw = redis.lrange(KEY, 0, -1);
            pendingRaw = redis.hvals(PENDING_KEY);
        } else {
            synchronized (memory) {
                raw = List.copyOf(memory);
                pendingRaw = List.copyOf(pending.values());
            }
        }
        var rows = new ArrayList<Gateway.Spend>();
        var seenPending = new HashSet<String>();
        var completed = new HashSet<String>();
        for (var item : raw) {
            var row = decode(item);
            if (row != null) {
                rows.add(row);
                if ("PENDING".equals(row.status())) seenPending.add(row.requestId());
                if ("SETTLED".equals(row.status())) completed.add(row.requestId());
            }
        }
        rows.removeIf(row -> "PENDING".equals(row.status()) && completed.contains(row.requestId()));
        for (var item : pendingRaw) {
            var row = decode(item);
            if (row != null && !completed.contains(row.requestId()) && seenPending.add(row.requestId())) rows.add(row);
        }
        return rows;
    }

    private static String encode(Gateway.Spend row) {
        ObjectNode node = JSON.createObjectNode();
        node.put("alias", row.alias());
        node.put("model", row.model());
        node.put("inputTokens", row.inputTokens());
        node.put("outputTokens", row.outputTokens());
        if (row.costCny() == null) node.putNull("costCny");
        else node.put("costCny", row.costCny().toPlainString());
        node.put("status", row.status());
        node.put("reservedCny", row.reservedCny().toPlainString());
        node.put("requestId", row.requestId());
        node.put("ts", row.ts().toString());
        node.put("latencyMs", row.latencyMs());
        node.put("timedOut", row.timedOut());
        return node.toString();
    }

    private static Gateway.Spend decode(String raw) {
        try {
            var node = JSON.readTree(raw);
            return new Gateway.Spend(
                    node.path("alias").asText(""),
                    node.path("model").asText(""),
                    node.path("inputTokens").asInt(),
                    node.path("outputTokens").asInt(),
                    node.path("costCny").isNull() ? null : new BigDecimal(node.path("costCny").asText("0")),
                    node.path("requestId").asText(""),
                    Instant.parse(node.path("ts").asText()),
                    node.path("latencyMs").asLong(),
                    node.path("timedOut").asBoolean(false),
                    node.path("status").asText("SETTLED"),
                    new BigDecimal(node.path("reservedCny").asText("0")));
        } catch (Exception e) {
            return null;
        }
    }
}
