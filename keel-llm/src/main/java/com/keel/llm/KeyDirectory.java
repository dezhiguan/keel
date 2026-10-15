package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
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

    public Gateway.VirtualKey rotate(Gateway.VirtualKey key) {
        return decode(backend.rotate(ALIAS_PREFIX + key.alias(), TOKEN_PREFIX + key.token(),
                encode(key), key.alias()));
    }

    public void save(Gateway.VirtualKey key) {
        if (!backend.updateIfCurrent(ALIAS_PREFIX + key.alias(), encode(key), key.token(), key.version())) {
            throw new IllegalStateException("虚拟 Key 已轮换，请重试");
        }
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
        var current = byAlias(alias);
        return current != null && current.token().equals(token) ? current : null;
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
        node.put("version", key.version());
        if (key.rpm() != null) node.put("rpm", key.rpm());
        if (key.tpm() != null) node.put("tpm", key.tpm());
        if (key.concurrency() != null) node.put("concurrency", key.concurrency());
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
                    node.path("blocked").asBoolean(false),
                    node.path("version").asInt(1),
                    optionalInt(node.path("rpm")),
                    optionalInt(node.path("tpm")),
                    optionalInt(node.path("concurrency")));
        } catch (Exception e) {
            throw new IllegalStateException("虚拟 Key 无法读取", e);
        }
    }

    private static Integer optionalInt(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private static List<String> texts(com.fasterxml.jackson.databind.JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    interface Backend {
        String rotate(String aliasKey, String tokenKey, String value, String alias);

        boolean updateIfCurrent(String aliasKey, String value, String token, int version);

        String get(String key);

        List<String> values(String prefix);
    }

    static final class MapBackend implements Backend {
        private final ConcurrentHashMap<String, String> data;

        MapBackend(ConcurrentHashMap<String, String> data) {
            this.data = data;
        }

        @Override
        public String rotate(String aliasKey, String tokenKey, String value, String alias) {
            synchronized (data) {
                var current = decode(data.get(aliasKey));
                var next = decode(value);
                var rotated = new Gateway.VirtualKey(next.alias(), next.token(), next.models(), next.fallback(),
                        next.dailyBudgetCny(), next.allowFallback(), next.blocked(),
                        current == null ? 1 : current.version() + 1, next.rpm(), next.tpm(), next.concurrency());
                var encoded = encode(rotated);
                data.put(tokenKey, alias);
                data.put(aliasKey, encoded);
                return encoded;
            }
        }

        @Override
        public boolean updateIfCurrent(String aliasKey, String value, String token, int version) {
            synchronized (data) {
                var current = decode(data.get(aliasKey));
                if (current == null || !current.token().equals(token) || current.version() != version) {
                    return false;
                }
                data.put(aliasKey, value);
                return true;
            }
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
        private static final String ROTATE = """
                local previous = redis.call('GET', KEYS[1])
                local version = 1
                if previous then
                  local old = cjson.decode(previous)
                  version = tonumber(old.version or 1) + 1
                end
                local next = cjson.decode(ARGV[1])
                next.version = version
                local encoded = cjson.encode(next)
                redis.call('SET', KEYS[2], ARGV[2])
                redis.call('SET', KEYS[1], encoded)
                return encoded
                """;
        private static final String UPDATE = """
                local raw = redis.call('GET', KEYS[1])
                if not raw then return 0 end
                local current = cjson.decode(raw)
                if current.token ~= ARGV[2] or tonumber(current.version or 1) ~= tonumber(ARGV[3]) then
                  return 0
                end
                redis.call('SET', KEYS[1], ARGV[1])
                return 1
                """;
        private final RedisCommands<String, String> redis;

        RedisBackend(String redisUri) {
            this.redis = RedisClient.create(redisUri).connect().sync();
        }

        @Override
        public String rotate(String aliasKey, String tokenKey, String value, String alias) {
            return redis.eval(ROTATE, ScriptOutputType.VALUE, new String[]{aliasKey, tokenKey}, value, alias);
        }

        @Override
        public boolean updateIfCurrent(String aliasKey, String value, String token, int version) {
            Long updated = redis.eval(UPDATE, ScriptOutputType.INTEGER, new String[]{aliasKey},
                    value, token, Integer.toString(version));
            return updated != null && updated == 1;
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
