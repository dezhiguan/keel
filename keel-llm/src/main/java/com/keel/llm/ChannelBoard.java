package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Extra vendor channels and which channel a virtual key uses for a model. */
public final class ChannelBoard {
    public record Added(String id, String name, String model, String env, int rpm) {}

    private static final String KEY = "keel-llm:channels:v1";
    private static final String BIND = "keel-llm:channel-bind:v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RedisCommands<String, String> redis;
    private final List<Added> local = new ArrayList<>();
    private final ConcurrentHashMap<String, String> bindings = new ConcurrentHashMap<>();
    private int next = 1;

    public ChannelBoard(String redisUri) {
        redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public Added add(String name, String model, String env, int rpm) {
        if (redis == null) {
            synchronized (local) {
                var added = new Added("c-" + next++, name, model, env, rpm);
                local.add(added);
                return added;
            }
        }
        long id = redis.incr(KEY + ":next");
        var added = new Added("c-" + id, name, model, env, rpm);
        try {
            redis.rpush(KEY, JSON.writeValueAsString(added));
        } catch (Exception error) {
            throw new IllegalStateException("渠道保存失败", error);
        }
        return added;
    }

    public Added find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (var added : added()) {
            if (added.id().equals(id)) {
                return added;
            }
        }
        return null;
    }

    public List<Added> added() {
        if (redis == null) {
            synchronized (local) {
                return List.copyOf(local);
            }
        }
        try {
            var rows = new ArrayList<Added>();
            for (var raw : redis.lrange(KEY, 0, -1)) {
                rows.add(JSON.readValue(raw, Added.class));
            }
            return rows;
        } catch (Exception error) {
            throw new IllegalStateException("渠道读取失败", error);
        }
    }

    /** Null means the key still uses the built-in channel of that model. */
    public String binding(String alias, String model) {
        var field = field(alias, model);
        if (redis == null) {
            return bindings.get(field);
        }
        var value = redis.hget(BIND, field);
        return value == null || value.isBlank() ? null : value;
    }

    public void bind(String alias, String model, String channelId) {
        var field = field(alias, model);
        if (channelId == null || channelId.isBlank()) {
            if (redis == null) {
                bindings.remove(field);
            } else {
                redis.hdel(BIND, field);
            }
            return;
        }
        if (redis == null) {
            bindings.put(field, channelId);
        } else {
            redis.hset(BIND, field, channelId);
        }
    }

    private static String field(String alias, String model) {
        return alias + "\n" + model;
    }
}
