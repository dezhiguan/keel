package com.keel.server.insight;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentUsageServiceTest {
    @Test void countsEachTraceOnceAndUsesTheGatewayDailySpend() throws Exception {
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            paths.add(path + (query.isBlank() ? "" : "?" + query));
            String body = switch (path) {
                case "/api/public/v2/observations" -> query.contains("cursor=")
                        ? "{\"data\":[{\"traceId\":\"t3\",\"metadata\":{\"keel.agent\":\"echo\",\"keel.llm.key_alias\":\"echo-dev\"}}],\"meta\":{}}"
                        : "{\"data\":[{\"traceId\":\"t1\",\"metadata\":{\"keel.agent\":\"askdb\"}},{\"traceId\":\"t2\",\"metadata\":{\"keel.agent\":\"askdb\",\"keel.llm.key_alias\":\"askdb-prod\"}}],\"meta\":{\"cursor\":\"next\"}}";
                case "/api/public/v3/scores" -> "{\"data\":[{\"name\":\"correctness\",\"value\":0.88,\"metadata\":{\"keel.agent\":\"askdb\"}}]}";
                case "/admin/v1/keys" -> "{\"data\":[{\"alias\":\"askdb-prod\",\"models\":[\"qwen-plus\"],\"dailyBudgetCny\":40,\"spentCny\":12.5,\"blocked\":false},{\"alias\":\"echo-dev\",\"models\":[\"qwen-plus\"],\"dailyBudgetCny\":30,\"spentCny\":0.4,\"blocked\":false}]}";
                default -> "{\"data\":[]}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var usage = usage(base, (from, env) -> "all".equals(env)
                ? List.of(new SavedTraces.TraceHit("t2", "askdb"), new SavedTraces.TraceHit("t4", "echo"))
                : List.of(new SavedTraces.TraceHit("t2", "askdb")));
        var all = usage.apply(List.of(summary("askdb"), summary("echo")), "all");
        assertThat(all).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo("askdb");
            assertThat(row.calls24h()).isEqualTo(2L);
            assertThat(row.callsTotal()).isGreaterThanOrEqualTo(row.calls24h());
            assertThat(row.costCny()).isEqualTo(12.5);
            assertThat(row.score()).isEqualTo(0.88);
        });
        assertThat(all).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo("echo");
            assertThat(row.calls24h()).isEqualTo(1L);
            assertThat(row.costCny()).isEqualTo(0.4);
            assertThat(row.score()).isNull();
        });
        var prod = usage.apply(List.of(summary("askdb"), summary("echo")), "prod");
        assertThat(prod).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo("askdb");
            assertThat(row.calls24h()).isEqualTo(1L);
            assertThat(row.costCny()).isEqualTo(12.5);
        });
        assertThat(prod).anySatisfy(row -> {
            assertThat(row.name()).isEqualTo("echo");
            assertThat(row.calls24h()).isEqualTo(0L);
            assertThat(row.costCny()).isEqualTo(0.0);
        });
        assertThat(paths).noneMatch(path -> path.startsWith("/api/public/metrics") || path.startsWith("/api/public/traces")
                || path.startsWith("/api/public/scores") || path.contains("/spend/logs"));
        assertThat(paths).filteredOn(path -> path.startsWith("/api/public/v2/observations"))
                .isNotEmpty()
                .allMatch(path -> path.contains("fromStartTime=2026-10-04"))
                .noneMatch(path -> path.contains("2020-01-01") || path.contains("cursor="));
        server.stop(0);
    }

    @Test void servesThePreviousSnapshotWhileRefreshing() throws Exception {
        var bodies = new AtomicReference<>("""
                {"data":[{"traceId":"t1","metadata":{"keel.agent":"echo"}}],"meta":{}}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/v2/observations", exchange -> write(exchange, bodies.get()));
        server.createContext("/", exchange -> write(exchange, "{\"data\":[]}"));
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var later = new Later();
        var clock = new MutableClock(Instant.parse("2026-10-05T08:00:00Z"));
        var usage = new AgentUsageService(new LangfuseClient(base, "pk", "sk"), new LiteLlmClient("", ""),
                new QualityService(new LangfuseClient("", "", "")),
                new EvalQueryService(new LangfuseClient(base, "pk", "sk"), name -> null),
                SavedTraces.EMPTY, clock, Executors.newVirtualThreadPerTaskExecutor(), later);
        assertThat(usage.apply(summary("echo"), "all").calls24h()).isEqualTo(1L);
        bodies.set("""
                {"data":[{"traceId":"t8","metadata":{"keel.agent":"echo"}},{"traceId":"t9","metadata":{"keel.agent":"echo"}}],"meta":{}}
                """);
        clock.advance(Duration.ofMinutes(4));
        assertThat(usage.apply(summary("echo"), "all").calls24h()).isEqualTo(1L);
        later.flush();
        assertThat(usage.apply(summary("echo"), "all").calls24h()).isEqualTo(2L);
        server.stop(0);
    }

    @Test void leavesCostBlankWhenTheGatewayIsNotConfigured() {
        var usage = new AgentUsageService(new LangfuseClient("", "", ""), new LiteLlmClient("", ""),
                new QualityService(new LangfuseClient("", "", "")),
                new EvalQueryService(new LangfuseClient("", "", ""), name -> null), new SavedTraces() {
            @Override public void save(String agent, String env, String traceId, String question, int durationMs) {}
            @Override public java.util.Map<String, Object> list(int page, int size, String agent) { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> detail(String traceId) { return null; }
            @Override public List<SavedTraces.TraceHit> since(Instant from, String env) {
                return List.of(new SavedTraces.TraceHit("local-1", "echo"));
            }
        }, Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC));
        var row = usage.apply(summary("echo"), "all");
        assertThat(row.calls24h()).isEqualTo(1L);
        assertThat(row.costCny()).isNull();
        assertThat(row.score()).isNull();
    }

    private static AgentUsageService usage(String base, java.util.function.BiFunction<Instant, String, List<SavedTraces.TraceHit>> hits) {
        return new AgentUsageService(new LangfuseClient(base, "pk", "sk"), new LiteLlmClient(base, "admin"),
                new QualityService(new LangfuseClient(base, "pk", "sk")),
                new EvalQueryService(new LangfuseClient(base, "pk", "sk"), name -> null), new SavedTraces() {
            @Override public void save(String agent, String env, String traceId, String question, int durationMs) {}
            @Override public java.util.Map<String, Object> list(int page, int size, String agent) { return java.util.Map.of(); }
            @Override public java.util.Map<String, Object> detail(String traceId) { return null; }
            @Override public List<SavedTraces.TraceHit> since(Instant from, String env) { return hits.apply(from, env); }
        }, Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"), ZoneOffset.UTC));
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class Later implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void flush() {
            var pending = List.copyOf(tasks);
            tasks.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static AgentSummary summary(String name) {
        var agent = new Agent();
        agent.setName(name);
        agent.setDisplayName(name);
        agent.setStatus(AgentStatus.ONLINE);
        agent.setRuntime("code");
        agent.setOwnerOrg("组");
        agent.setOwnerUser("amy");
        return AgentSummary.of(agent);
    }
}
