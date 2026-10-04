package com.keel.llm;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Daily spend in CNY micro-units, reset on the Asia/Shanghai calendar day. */
public final class BudgetCounter implements AutoCloseable {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal SCALE = new BigDecimal("1000000");

    private final ConcurrentHashMap<String, AtomicLong> local = new ConcurrentHashMap<>();
    private final RedisCommands<String, String> redis;

    public BudgetCounter() {
        this.redis = null;
    }

    public BudgetCounter(String redisUri) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public BigDecimal add(String alias, BigDecimal costCny) {
        var micros = costCny.multiply(SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
        var key = alias + ":" + LocalDate.now(SHANGHAI);
        long total;
        if (redis != null) {
            total = redis.incrby("keel-llm:budget:" + key, micros);
        } else {
            total = local.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(micros);
        }
        return BigDecimal.valueOf(total).divide(SCALE, 6, RoundingMode.HALF_UP);
    }

    public BigDecimal spent(String alias) {
        var key = alias + ":" + LocalDate.now(SHANGHAI);
        long total = redis != null
                ? Long.parseLong(redis.get("keel-llm:budget:" + key) == null ? "0" : redis.get("keel-llm:budget:" + key))
                : local.getOrDefault(key, new AtomicLong()).get();
        return BigDecimal.valueOf(total).divide(SCALE, 6, RoundingMode.HALF_UP);
    }

    @Override
    public void close() {
        if (redis != null) {
            redis.getStatefulConnection().close();
        }
    }
}
