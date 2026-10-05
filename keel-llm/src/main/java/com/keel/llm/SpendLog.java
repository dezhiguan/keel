package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Recent model calls, shared by every gateway replica. Redis keeps them across restarts. */
public final class SpendLog {
    static final String KEY = "keel-llm:spend";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX = 5000;

    private final RedisCommands<String, String> redis;
    private final List<String> memory = new ArrayList<>();

    public SpendLog(String redisUri) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public void append(Gateway.Spend row) {
        var encoded = encode(row);
        if (redis != null) {
            redis.rpush(KEY, encoded);
            redis.ltrim(KEY, -MAX, -1);
            return;
        }
        synchronized (memory) {
            memory.add(encoded);
            if (memory.size() > MAX) {
                memory.remove(0);
            }
        }
    }

    public List<Gateway.Spend> all() {
        List<String> raw;
        if (redis != null) {
            raw = redis.lrange(KEY, 0, -1);
        } else {
            synchronized (memory) {
                raw = List.copyOf(memory);
            }
        }
        var rows = new ArrayList<Gateway.Spend>();
        for (var item : raw) {
            var row = decode(item);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static String encode(Gateway.Spend row) {
        ObjectNode node = JSON.createObjectNode();
        node.put("alias", row.alias());
        node.put("model", row.model());
        node.put("inputTokens", row.inputTokens());
        node.put("outputTokens", row.outputTokens());
        node.put("costCny", row.costCny().toPlainString());
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
                    new BigDecimal(node.path("costCny").asText("0")),
                    node.path("requestId").asText(""),
                    Instant.parse(node.path("ts").asText()),
                    node.path("latencyMs").asLong(),
                    node.path("timedOut").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }
}
