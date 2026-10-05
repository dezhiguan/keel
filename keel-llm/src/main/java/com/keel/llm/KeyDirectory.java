package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Virtual keys shared by every gateway replica. Redis is the store when configured. */
public final class KeyDirectory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALIAS_PREFIX = "keel-llm:vkey:alias:";
    private static final String TOKEN_PREFIX = "keel-llm:vkey:token:";

    private final Backend backend;

    public KeyDirectory() {
        this(new MapBackend(new ConcurrentHashMap<>()));
    }

    public KeyDirectory(String redisUri) {
        this(redisUri == null || redisUri.isBlank()
                ? new MapBackend(new ConcurrentHashMap<>())
                : new RedisBackend(redisUri));
    }

    KeyDirectory(Backend backend) {
        this.backend = backend;
    }

    public void save(Gateway.VirtualKey key) {
        backend.put(ALIAS_PREFIX + key.alias(), encode(key));
        backend.put(TOKEN_PREFIX + key.token(), key.alias());
    }

    public List<Gateway.VirtualKey> list() {
        var found = new ArrayList<Gateway.VirtualKey>();
        for (var raw : backend.values(ALIAS_PREFIX)) {
            var key = decode(raw);
            if (key != null) {
                found.add(key);
            }
        }
        return List.copyOf(found);
    }

    public Gateway.VirtualKey byAlias(String alias) {
        return decode(backend.get(ALIAS_PREFIX + alias));
    }

    public Gateway.VirtualKey byToken(String token) {
        var alias = backend.get(TOKEN_PREFIX + token);
        if (alias == null || alias.isBlank()) {
            return null;
        }
        return byAlias(alias);
    }

    private static String encode(Gateway.VirtualKey key) {
        var node = JSON.createObjectNode();
        node.put("alias", key.alias());
        node.put("token", key.token());
        var models = node.putArray("models");
        key.models().forEach(models::add);
        var fallback = node.putArray("fallback");
        key.fallback().forEach(fallback::add);
        node.put("dailyBudgetCny", key.dailyBudgetCny().toPlainString());
        node.put("allowFallback", key.allowFallback());
        node.put("blocked", key.blocked());
        return node.toString();
    }

    private static Gateway.VirtualKey decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            var node = JSON.readTree(raw);
            return new Gateway.VirtualKey(
                    node.path("alias").asText(),
                    node.path("token").asText(),
                    texts(node.path("models")),
                    texts(node.path("fallback")),
                    new BigDecimal(node.path("dailyBudgetCny").asText("0")),
                    node.path("allowFallback").asBoolean(false),
                    node.path("blocked").asBoolean(false));
        } catch (Exception e) {
            throw new IllegalStateException("虚拟 Key 无法读取", e);
        }
    }

    private static List<String> texts(com.fasterxml.jackson.databind.JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    interface Backend {
        void put(String key, String value);

        String get(String key);

        List<String> values(String prefix);
    }

    static final class MapBackend implements Backend {
        private final ConcurrentHashMap<String, String> data;

        MapBackend(ConcurrentHashMap<String, String> data) {
            this.data = data;
        }

        @Override
        public void put(String key, String value) {
            data.put(key, value);
        }

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public List<String> values(String prefix) {
            return data.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(java.util.Map.Entry::getValue)
                    .toList();
        }
    }

    static final class RedisBackend implements Backend {
        private final RedisCommands<String, String> redis;

        RedisBackend(String redisUri) {
            this.redis = RedisClient.create(redisUri).connect().sync();
        }

        @Override
        public void put(String key, String value) {
            redis.set(key, value);
        }

        @Override
        public String get(String key) {
            return redis.get(key);
        }

        @Override
        public List<String> values(String prefix) {
            var found = new ArrayList<String>();
            ScanCursor cursor = ScanCursor.INITIAL;
            var args = ScanArgs.Builder.matches(prefix + "*").limit(200);
            do {
                KeyScanCursor<String> page = redis.scan(cursor, args);
                for (var key : page.getKeys()) {
                    var value = redis.get(key);
                    if (value != null) {
                        found.add(value);
                    }
                }
                cursor = page;
            } while (!cursor.isFinished());
            return found;
        }
    }
}
