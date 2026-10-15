package com.keel.llm;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.HashSet;
import java.util.Set;

/** Models that answered a probe. Redis shares the set across gateway replicas. */
public final class ProbeMarks {
    static final String KEY = "keel-llm:probed";

    private final RedisCommands<String, String> redis;
    private final Set<String> memory = new HashSet<>();

    public ProbeMarks(String redisUri) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public void add(String name) {
        if (redis != null) {
            redis.sadd(KEY, name);
            return;
        }
        synchronized (memory) {
            memory.add(name);
        }
    }

    public boolean contains(String name) {
        if (redis != null) {
            return Boolean.TRUE.equals(redis.sismember(KEY, name));
        }
        synchronized (memory) {
            return memory.contains(name);
        }
    }
}
