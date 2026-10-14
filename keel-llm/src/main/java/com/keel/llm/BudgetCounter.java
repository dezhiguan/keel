package com.keel.llm;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Atomic daily CNY ledger. Redis Lua scripts provide one ledger across gateway replicas. */
public final class BudgetCounter implements AutoCloseable {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal SCALE = new BigDecimal("1000000");
    private static final long RESERVATION_MILLIS = 120_000;
    private static final int RETENTION_SECONDS = 172_800;
    private static final int PENDING_RETENTION_SECONDS = 2_592_000;
    private static final String PREFIX = "keel-llm:budget:v2:";
    private static final String CLEANUP = """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              local legacy = redis.call('GET', KEYS[4])
              if legacy then
                redis.call('HSET', KEYS[1], 'settled', legacy)
                redis.call('EXPIRE', KEYS[1], 172800)
              end
            end
            local expired = redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[1])
            for _, id in ipairs(expired) do
              local raw = redis.call('HGET', KEYS[2], id)
              if raw then
                local row = cjson.decode(raw)
                if row.s == 'R' then
                  redis.call('HINCRBY', KEYS[1], 'reserved', -tonumber(row.r))
                  row.s = 'E'
                  redis.call('HSET', KEYS[2], id, cjson.encode(row))
                end
              end
              redis.call('ZREM', KEYS[3], id)
            end
            """;
    private static final String RESERVE = CLEANUP + """
            local old = redis.call('HGET', KEYS[2], ARGV[2])
            if old then
              local row = cjson.decode(old)
              if row.s == 'R' then return 2 end
              return 3
            end
            local settled = tonumber(redis.call('HGET', KEYS[1], 'settled') or '0')
            local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
            local amount = tonumber(ARGV[3])
            if settled + reserved + amount > tonumber(ARGV[4]) then return 0 end
            redis.call('HINCRBY', KEYS[1], 'reserved', amount)
            redis.call('HSET', KEYS[2], ARGV[2], cjson.encode({s='R', r=amount, a=0}))
            redis.call('ZADD', KEYS[3], tonumber(ARGV[5]), ARGV[2])
            for i=1,3 do
              local ttl = redis.call('TTL', KEYS[i])
              if ttl == -1 or (ttl >= 0 and ttl < tonumber(ARGV[6])) then
                redis.call('EXPIRE', KEYS[i], tonumber(ARGV[6]))
              end
            end
            return 1
            """;
    private static final String SETTLE = CLEANUP + """
            local raw = redis.call('HGET', KEYS[2], ARGV[2])
            if not raw then return -1 end
            local row = cjson.decode(raw)
            if row.s == 'S' then return 0 end
            if row.s ~= 'R' and row.s ~= 'P' then return -1 end
            redis.call('HINCRBY', KEYS[1], 'reserved', -tonumber(row.r))
            redis.call('HINCRBY', KEYS[1], 'settled', tonumber(ARGV[3]))
            row.s = 'S'
            row.a = tonumber(ARGV[3])
            redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(row))
            redis.call('ZREM', KEYS[3], ARGV[2])
            return 1
            """;
    private static final String RELEASE = CLEANUP + """
            local raw = redis.call('HGET', KEYS[2], ARGV[2])
            if not raw then return 0 end
            local row = cjson.decode(raw)
            if row.s ~= 'R' then return 0 end
            redis.call('HINCRBY', KEYS[1], 'reserved', -tonumber(row.r))
            row.s = 'X'
            redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(row))
            redis.call('ZREM', KEYS[3], ARGV[2])
            return 1
            """;
    private static final String MARK_PENDING = CLEANUP + """
            local raw = redis.call('HGET', KEYS[2], ARGV[2])
            if not raw then return -1 end
            local row = cjson.decode(raw)
            if row.s == 'P' then return 0 end
            if row.s ~= 'R' then return -1 end
            row.s = 'P'
            redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(row))
            redis.call('ZREM', KEYS[3], ARGV[2])
            for i=1,3 do redis.call('EXPIRE', KEYS[i], tonumber(ARGV[3])) end
            return 1
            """;
    private static final String SNAPSHOT = CLEANUP + """
            return {tonumber(redis.call('HGET', KEYS[1], 'settled') or '0'),
                    tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')}
            """;

    private final ConcurrentHashMap<String, Day> local = new ConcurrentHashMap<>();
    private final RedisCommands<String, String> redis;
    private final Clock clock;

    public BudgetCounter() { this(null, Clock.systemUTC()); }

    public BudgetCounter(String redisUri) { this(redisUri, Clock.systemUTC()); }

    BudgetCounter(Clock clock) { this(null, clock); }

    private BudgetCounter(String redisUri, Clock clock) {
        this.redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
        this.clock = clock;
    }

    public boolean reserve(String alias, String requestId, BigDecimal maxCostCny, BigDecimal dailyBudgetCny) {
        long estimate = micros(maxCostCny, RoundingMode.CEILING);
        long limit = micros(dailyBudgetCny, RoundingMode.FLOOR);
        if (estimate <= 0 || limit <= 0) throw new IllegalArgumentException("预留额与日预算必须大于零");
        long now = clock.millis();
        if (redis != null) {
            Long result = redis.eval(RESERVE, ScriptOutputType.INTEGER, keys(alias, requestId),
                    Long.toString(now), requestId, Long.toString(estimate), Long.toString(limit),
                    Long.toString(now + RESERVATION_MILLIS), Integer.toString(RETENTION_SECONDS));
            return result != null && (result == 1 || result == 2);
        }
        var day = day(alias, requestId);
        synchronized (day) {
            cleanup(day, now);
            var old = day.requests.get(requestId);
            if (old != null) return old.state == State.RESERVED;
            if (day.settled + day.reserved + estimate > limit) return false;
            day.reserved += estimate;
            day.requests.put(requestId, new Reservation(State.RESERVED, estimate, now + RESERVATION_MILLIS));
            return true;
        }
    }

    /** True only on the first successful settlement. */
    public boolean settle(String alias, String requestId, BigDecimal actualCostCny) {
        long actual = micros(actualCostCny, RoundingMode.HALF_UP);
        if (actual < 0) throw new IllegalArgumentException("实际成本不能为负");
        long now = clock.millis();
        if (redis != null) {
            Long result = redis.eval(SETTLE, ScriptOutputType.INTEGER, keys(alias, requestId),
                    Long.toString(now), requestId, Long.toString(actual));
            if (result == null || result < 0) throw new IllegalStateException("预留已过期，不能结算");
            return result == 1;
        }
        var day = day(alias, requestId);
        synchronized (day) {
            cleanup(day, now);
            var row = day.requests.get(requestId);
            if (row == null || row.state == State.EXPIRED || row.state == State.RELEASED) {
                throw new IllegalStateException("预留已过期，不能结算");
            }
            if (row.state == State.SETTLED) return false;
            day.reserved -= row.amount;
            day.settled += actual;
            row.state = State.SETTLED;
            return true;
        }
    }

    /** Keep the estimate charged against the daily limit until the unknown usage can be reconciled. */
    public boolean markPending(String alias, String requestId) {
        long now = clock.millis();
        if (redis != null) {
            Long result = redis.eval(MARK_PENDING, ScriptOutputType.INTEGER, keys(alias, requestId),
                    Long.toString(now), requestId, Integer.toString(PENDING_RETENTION_SECONDS));
            if (result == null || result < 0) throw new IllegalStateException("预留已过期，不能标记待计费");
            return result == 1;
        }
        var day = day(alias, requestId);
        synchronized (day) {
            cleanup(day, now);
            var row = day.requests.get(requestId);
            if (row == null || row.state == State.EXPIRED || row.state == State.RELEASED || row.state == State.SETTLED) {
                throw new IllegalStateException("预留已过期，不能标记待计费");
            }
            if (row.state == State.PENDING) return false;
            row.state = State.PENDING;
            return true;
        }
    }

    public void release(String alias, String requestId) {
        long now = clock.millis();
        if (redis != null) {
            redis.eval(RELEASE, ScriptOutputType.INTEGER, keys(alias, requestId), Long.toString(now), requestId);
            return;
        }
        var day = day(alias, requestId);
        synchronized (day) {
            cleanup(day, now);
            var row = day.requests.get(requestId);
            if (row != null && row.state == State.RESERVED) {
                day.reserved -= row.amount;
                row.state = State.RELEASED;
            }
        }
    }

    public Snapshot snapshot(String alias) {
        long now = clock.millis();
        if (redis != null) {
            List<?> raw = redis.eval(SNAPSHOT, ScriptOutputType.MULTI, keys(alias, ""), Long.toString(now));
            return new Snapshot(cny(((Number) raw.get(0)).longValue()), cny(((Number) raw.get(1)).longValue()));
        }
        var day = day(alias, "");
        synchronized (day) {
            cleanup(day, now);
            return new Snapshot(cny(day.settled), cny(day.reserved));
        }
    }

    public BigDecimal spent(String alias) { return snapshot(alias).settledCny(); }

    private String[] keys(String alias, String requestId) {
        var suffix = alias + ":" + date(requestId);
        var base = PREFIX + suffix;
        return new String[]{base, base + ":requests", base + ":expiry", "keel-llm:budget:" + suffix};
    }

    private Day day(String alias, String requestId) {
        var key = alias + ":" + date(requestId);
        return local.computeIfAbsent(key, ignored -> new Day());
    }

    private LocalDate date(String requestId) {
        if (requestId != null && requestId.length() > 11 && requestId.charAt(10) == '@') {
            return LocalDate.parse(requestId.substring(0, 10));
        }
        return LocalDate.now(clock.withZone(SHANGHAI));
    }

    private static void cleanup(Day day, long now) {
        for (var row : day.requests.values()) {
            if (row.state == State.RESERVED && row.expiresAt <= now) {
                day.reserved -= row.amount;
                row.state = State.EXPIRED;
            }
        }
    }

    private static long micros(BigDecimal cny, RoundingMode mode) {
        return cny.multiply(SCALE).setScale(0, mode).longValueExact();
    }

    private static BigDecimal cny(long micros) {
        return BigDecimal.valueOf(micros).divide(SCALE, 6, RoundingMode.UNNECESSARY);
    }

    @Override public void close() {
        if (redis != null) redis.getStatefulConnection().close();
    }

    public record Snapshot(BigDecimal settledCny, BigDecimal reservedCny) {}
    private enum State { RESERVED, PENDING, SETTLED, RELEASED, EXPIRED }
    private static final class Reservation {
        State state;
        final long amount;
        final long expiresAt;
        Reservation(State state, long amount, long expiresAt) {
            this.state = state;
            this.amount = amount;
            this.expiresAt = expiresAt;
        }
    }
    private static final class Day {
        long settled;
        long reserved;
        final Map<String, Reservation> requests = new HashMap<>();
    }
}
