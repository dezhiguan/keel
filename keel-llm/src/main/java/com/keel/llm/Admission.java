package com.keel.llm;

import com.keel.common.error.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-key RPM, TPM and concurrency. Redis keeps the counters across replicas. */
public final class Admission {
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final long WINDOW_MILLIS = 60_000;
    private final RedisCommands<String, String> redis;
    private final Map<String, Window> local = new ConcurrentHashMap<>();

    public Admission(String redisUri) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public void enter(Gateway.VirtualKey key) {
        if (unlimited(key)) {
            return;
        }
        if (redis != null) {
            enterRedis(key);
            return;
        }
        var window = local.computeIfAbsent(key.alias(), ignored -> new Window());
        synchronized (window) {
            long now = System.currentTimeMillis();
            window.drop(now);
            if (key.rpm() != null && window.hits.size() >= key.rpm()) {
                throw limited();
            }
            if (key.tpm() != null && window.tokens() >= key.tpm()) {
                throw limited();
            }
            if (key.concurrency() != null && window.inFlight >= key.concurrency()) {
                throw limited();
            }
            if (key.rpm() != null) {
                window.hits.add(now);
            }
            if (key.concurrency() != null) {
                window.inFlight++;
            }
        }
    }

    public void leave(Gateway.VirtualKey key, int tokens) {
        if (unlimited(key)) {
            return;
        }
        if (redis != null) {
            leaveRedis(key, tokens);
            return;
        }
        var window = local.get(key.alias());
        if (window == null) {
            return;
        }
        synchronized (window) {
            if (key.concurrency() != null && window.inFlight > 0) {
                window.inFlight--;
            }
            if (key.tpm() != null && tokens > 0) {
                window.usage.add(new Usage(System.currentTimeMillis(), tokens));
            }
        }
    }

    private void enterRedis(Gateway.VirtualKey key) {
        var minute = LocalDateTime.now(BudgetCounter.SHANGHAI).format(MINUTE);
        String rpmKey = "keel-llm:rpm:" + key.alias() + ":" + minute;
        String tpmKey = "keel-llm:tpm:" + key.alias() + ":" + minute;
        String concKey = "keel-llm:conc:" + key.alias();
        boolean countedRpm = false;
        boolean held = false;
        try {
            if (key.rpm() != null) {
                long count = redis.incr(rpmKey);
                redis.expire(rpmKey, 120);
                countedRpm = true;
                if (count > key.rpm()) {
                    throw limited();
                }
            }
            if (key.tpm() != null) {
                var current = redis.get(tpmKey);
                long used = current == null ? 0 : Long.parseLong(current);
                if (used >= key.tpm()) {
                    throw limited();
                }
            }
            if (key.concurrency() != null) {
                long count = redis.incr(concKey);
                held = true;
                if (count > key.concurrency()) {
                    throw limited();
                }
            }
        } catch (Gateway.GatewayException error) {
            if (countedRpm) {
                redis.decr(rpmKey);
            }
            if (held) {
                redis.decr(concKey);
            }
            throw error;
        }
    }

    private void leaveRedis(Gateway.VirtualKey key, int tokens) {
        if (key.concurrency() != null) {
            redis.decr("keel-llm:conc:" + key.alias());
        }
        if (key.tpm() != null && tokens > 0) {
            var minute = LocalDateTime.now(BudgetCounter.SHANGHAI).format(MINUTE);
            var tpmKey = "keel-llm:tpm:" + key.alias() + ":" + minute;
            redis.incrby(tpmKey, tokens);
            redis.expire(tpmKey, 120);
        }
    }

    private static boolean unlimited(Gateway.VirtualKey key) {
        return key.rpm() == null && key.tpm() == null && key.concurrency() == null;
    }

    private static Gateway.GatewayException limited() {
        return new Gateway.GatewayException(ErrorCode.LLM_RATE_LIMITED, ErrorCode.LLM_RATE_LIMITED.message());
    }

    private static final class Window {
        private final ArrayDeque<Long> hits = new ArrayDeque<>();
        private final ArrayDeque<Usage> usage = new ArrayDeque<>();
        private int inFlight;

        private void drop(long now) {
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MILLIS) {
                hits.removeFirst();
            }
            while (!usage.isEmpty() && now - usage.peekFirst().at >= WINDOW_MILLIS) {
                usage.removeFirst();
            }
        }

        private int tokens() {
            int total = 0;
            for (var item : usage) {
                total += item.tokens;
            }
            return total;
        }
    }

    private record Usage(long at, int tokens) {}
}
