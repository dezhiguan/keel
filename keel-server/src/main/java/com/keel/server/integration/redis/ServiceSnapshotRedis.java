package com.keel.server.integration.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SetArgs;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Disposable service-page snapshots only. Keys never overlap gateway budgets, tokens or billing. */
@Component
public class ServiceSnapshotRedis {
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String PUBLISH = """
            if redis.call('GET',KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET',KEYS[2],ARGV[2],'PX',ARGV[3])
            redis.call('DEL',KEYS[1])
            return 1
            """;
    private static final String RELEASE = """
            if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end
            return 0
            """;
    private static final String RENEW = """
            if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE',KEYS[1],ARGV[2]) end
            return 0
            """;
    private final String uri;
    private final ReentrantLock connectLock = new ReentrantLock();
    private volatile StatefulRedisConnection<String,String> connection;
    private volatile long retryAt;
    private volatile boolean closed;
    private RedisClient client;

    public ServiceSnapshotRedis(@Value("${KEEL_REDIS_URL:}") String uri) { this.uri = uri == null ? "" : uri; }
    public boolean configured() { return !uri.isBlank(); }
    private RedisCommands<String,String> commands() {
        if (closed || !configured() || System.nanoTime() < retryAt) throw new IllegalStateException("Service cache unavailable");
        var active = connection;
        if (active != null && active.isOpen()) return active.sync();
        connectLock.lock();
        try {
            if (connection == null || !connection.isOpen()) {
                if (client == null) {
                    var address = RedisURI.create(uri);
                    address.setTimeout(TIMEOUT);
                    client = RedisClient.create(address);
                    client.setDefaultTimeout(TIMEOUT);
                    client.setOptions(ClientOptions.builder().socketOptions(SocketOptions.builder().connectTimeout(TIMEOUT).build()).build());
                }
                connection = client.connect();
                connection.setTimeout(TIMEOUT);
            }
            return connection.sync();
        } catch (RuntimeException error) {
            unavailable();
            // Do not expose a Redis URI or authentication details through exceptions/logs.
            throw new IllegalStateException("Service cache connection unavailable");
        } finally { connectLock.unlock(); }
    }
    private void unavailable() { retryAt = System.nanoTime() + Duration.ofSeconds(5).toNanos(); }
    public String read(String scope) {
        try { return commands().get(key(scope,"snapshot")); }
        catch (RuntimeException error) { unavailable(); throw new IllegalStateException("Service cache read unavailable"); }
    }
    public String claim(String scope, Duration lease) {
        var owner = UUID.randomUUID().toString();
        try { return "OK".equals(commands().set(key(scope,"lock"),owner,SetArgs.Builder.nx().px(lease.toMillis()))) ? owner : null; }
        catch (RuntimeException error) { unavailable(); throw new IllegalStateException("Service cache lock unavailable"); }
    }
    public boolean publish(String scope,String owner,String payload,Duration retention) {
        try { return Long.valueOf(1).equals(commands().eval(PUBLISH,ScriptOutputType.INTEGER,
                new String[]{key(scope,"lock"),key(scope,"snapshot")},owner,payload,Long.toString(retention.toMillis()))); }
        catch (RuntimeException error) { unavailable(); throw new IllegalStateException("Service cache publish unavailable"); }
    }
    public boolean renew(String scope,String owner,Duration lease) {
        try { return Long.valueOf(1).equals(commands().eval(RENEW,ScriptOutputType.INTEGER,
                new String[]{key(scope,"lock")},owner,Long.toString(lease.toMillis()))); }
        catch (RuntimeException error) { unavailable(); throw new IllegalStateException("Service cache renew unavailable"); }
    }
    public void release(String scope,String owner) {
        try { commands().eval(RELEASE,ScriptOutputType.INTEGER,new String[]{key(scope,"lock")},owner); }
        catch (RuntimeException error) { unavailable(); }
    }
    static String key(String scope,String suffix) {
        if (!java.util.Set.of("all","dev","test","staging","prod").contains(scope)) throw new IllegalArgumentException("Invalid cache scope");
        return "keel:server:services:v1:{" + scope + "}:" + suffix;
    }
    @PreDestroy public void close() {
        closed = true;
        if (connection != null) connection.close();
        if (client != null) client.shutdown(Duration.ZERO,Duration.ofSeconds(2));
    }
}
