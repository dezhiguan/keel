package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.redis.ServiceSnapshotRedis;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Shared snapshots with stale-while-revalidate, plus a bounded local fallback during Redis outages. */
@Component
public class SharedServicesCache {
    static final Duration FRESH = Duration.ofSeconds(30);
    static final Duration RETENTION = Duration.ofMinutes(5);
    static final Duration LEASE = Duration.ofSeconds(90);
    private static final ExecutorService LOADERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final ScheduledExecutorService HEARTBEATS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("service-cache-lease").factory());
    private final ServiceSnapshotRedis redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String,Snapshot> local = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<Snapshot>> inflight = new ConcurrentHashMap<>();
    private final Map<String,Instant> nextAttempt = new ConcurrentHashMap<>();
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    record Snapshot(Map<String,Object> value, long at) {}

    @Autowired
    public SharedServicesCache(ServiceSnapshotRedis redis,ObjectMapper json) { this(redis,json,Clock.systemUTC()); }
    SharedServicesCache(ServiceSnapshotRedis redis,ObjectMapper json,Clock clock) { this.redis=redis;this.json=json;this.clock=clock; }

    public boolean sharedConfigured() { return redis != null && redis.configured(); }

    public Map<String,Object> get(String scope,Supplier<Map<String,Object>> loader) {
        var hit = read(scope);
        if (usable(hit)) {
            if (!fresh(hit)) schedule(scope,loader);
            return present(hit);
        }
        return present(refresh(scope,loader));
    }
    private Snapshot read(String scope) {
        if (redis != null && redis.configured()) {
            try {
                var text = redis.read(scope);
                if (text != null) {
                    var shared = json.readValue(text,Snapshot.class);
                    if (usable(shared)) {
                        var fallback = local.get(scope);
                        if (usable(fallback) && fallback.at()>shared.at()) return fallback;
                        local.put(scope,shared);
                        return shared;
                    }
                }
            } catch (Exception ignored) { /* Local fallback keeps its original collection timestamp. */ }
        }
        var hit = local.get(scope);
        if (hit != null && !usable(hit)) local.remove(scope,hit);
        return usable(hit) ? hit : null;
    }
    private boolean usable(Snapshot value) { return value != null && value.at()+RETENTION.toMillis()>clock.millis(); }
    private boolean fresh(Snapshot value) { return value != null && value.at()+FRESH.toMillis()>clock.millis(); }
    private void schedule(String scope,Supplier<Map<String,Object>> loader) {
        if (nextAttempt.getOrDefault(scope,Instant.EPOCH).isAfter(clock.instant()) || !scheduled.add(scope)) return;
        LOADERS.execute(() -> {
            try { refresh(scope,loader); }
            catch (RuntimeException ignored) { /* Keep the previous snapshot; never move its timestamp on failure. */ }
            finally { scheduled.remove(scope); }
        });
    }
    private Snapshot refresh(String scope,Supplier<Map<String,Object>> loader) {
        var created = new CompletableFuture<Snapshot>();
        var existing = inflight.putIfAbsent(scope,created);
        if (existing != null) return await(existing);
        String owner = null;
        ScheduledFuture<?> renewal = null;
        try {
            nextAttempt.put(scope,clock.instant().plusSeconds(5));
            boolean shared = redis != null && redis.configured();
            if (shared) {
                try { owner = redis.claim(scope,LEASE); }
                catch (RuntimeException unavailable) { shared = false; }
            }
            Snapshot result;
            if (shared && owner == null) {
                // Another process owns the refresh. A stale result is already useful;
                // a cold request waits a bounded time for publication without duplicate probes.
                result = waitForShared(scope);
            } else {
                var lost = new AtomicBoolean();
                if (owner != null) {
                    var token = owner;
                    renewal = HEARTBEATS.scheduleAtFixedRate(() -> {
                        try { if (!redis.renew(scope,token,LEASE)) lost.set(true); }
                        catch (RuntimeException unavailable) { lost.set(true); }
                    },20,20,TimeUnit.SECONDS);
                }
                result = new Snapshot(loader.get(),clock.millis());
                if (owner != null) {
                    if (lost.get()) throw unavailable();
                    try {
                        if (!redis.publish(scope,owner,json.writeValueAsString(result),RETENTION)) throw unavailable();
                    } catch (KeelException fenced) { throw fenced; }
                    catch (Exception unavailable) {
                        // Fresh upstream data is still valid locally when Redis fails during publication.
                    }
                }
                local.put(scope,result);
            }
            created.complete(result);
            return result;
        } catch (RuntimeException failure) {
            var previous = local.get(scope);
            if (usable(previous)) { created.complete(previous); return previous; }
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            if (renewal != null) renewal.cancel(false);
            if (owner != null) redis.release(scope,owner);
            inflight.remove(scope,created);
        }
    }
    private Snapshot waitForShared(String scope) {
        var deadline = System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while (System.nanoTime()<deadline) {
            var hit = read(scope);
            if (usable(hit)) return hit;
            try { Thread.sleep(100); }
            catch (InterruptedException error) { Thread.currentThread().interrupt();throw unavailable(); }
        }
        throw unavailable();
    }
    private static Snapshot await(CompletableFuture<Snapshot> pending) {
        try { return pending.get(20,TimeUnit.SECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt();throw unavailable(); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            throw unavailable();
        } catch (TimeoutException error) { throw unavailable(); }
    }
    private Map<String,Object> present(Snapshot snapshot) {
        var result = new LinkedHashMap<>(snapshot.value());
        result.put("dataStatus",Map.of("state",fresh(snapshot)?"ready":"stale","complete",true,
                "updatedAt",Instant.ofEpochMilli(snapshot.at()).toString()));
        return result;
    }
    private static KeelException unavailable() { return new KeelException(ErrorCode.INSIGHT_UPSTREAM_UNAVAILABLE,"共享服务数据正在刷新，请稍后重试"); }
}
