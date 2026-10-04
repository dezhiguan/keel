package com.keel.server.discovery;

import com.sun.net.httpserver.HttpServer;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReconcileJobTest {
    private static final Instant NOW = Instant.parse("2026-10-04T03:00:00Z");

    @Test void oneOpenRowPerKindAndResolveWhenTheConditionClears() {
        var book = new FindingBook(null);
        var job = new ReconcileJob(book, null, null, null);
        var zombie = input("ONLINE", true, true, true, "v1", "v1", false, false);
        job.apply("askdb", "dev", zombie);
        job.apply("askdb", "dev", zombie);
        assertThat(book.open("askdb", "dev")).hasSize(1);
        assertThat(book.open("askdb", "dev").get(0).kind()).isEqualTo("ZOMBIE");
        job.apply("askdb", "dev", input("ONLINE", true, true, true, "v1", "v1", true, false));
        assertThat(book.open("askdb", "dev")).isEmpty();
        assertThat(book.all("askdb", "dev").get(0).resolvedAt()).isNotNull();
    }

    @Test void eachAbnormalInputKeepsItsOwnKind() {
        var book = new FindingBook(null);
        var job = new ReconcileJob(book, null, null, null);
        job.apply("a", "dev", input("ONLINE", true, false, false, "v1", "v1", true, false));
        job.apply("b", "dev", input(null, false, true, true, null, null, null, null));
        job.apply("c", "dev", input("ONLINE", true, true, true, "v1", "v1", false, false));
        job.apply("d", "dev", input("RETIRED", true, true, true, "v1", "v1", false, false));
        job.apply("e", "dev", input("ONLINE", true, true, true, "v0", "v1", true, false));
        job.apply("f", "dev", input("ONLINE", true, true, true, "v1", "v1", true, false));
        assertThat(book.open("a", "dev")).extracting(FindingBook.Row::kind).containsExactly("OFFLINE");
        assertThat(book.open("b", "dev")).extracting(FindingBook.Row::kind).containsExactly("UNREGISTERED");
        assertThat(book.open("c", "dev")).extracting(FindingBook.Row::kind).containsExactly("ZOMBIE");
        assertThat(book.open("d", "dev")).extracting(FindingBook.Row::kind).containsExactly("RETIRE_INCOMPLETE");
        assertThat(book.open("e", "dev")).extracting(FindingBook.Row::kind).containsExactly("VERSION_MISMATCH");
        assertThat(book.open("f", "dev")).isEmpty();
    }

    @Test void bothTrafficSourcesDownDoesNotOpenAZombie() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var probe = new TrafficProbe(new LangfuseClient(base, "pk", "sk"), new LiteLlmClient(base, "admin"));
        assertThat(probe.langfuse(NOW)).isNull();
        assertThat(probe.gateway("askdb", "dev", NOW)).isNull();
        var book = new FindingBook(null);
        new ReconcileJob(book, probe, null, null).apply("askdb", "dev",
                input("ONLINE", true, true, true, "v1", "v1", probe.langfuse(NOW), probe.gateway("askdb", "dev", NOW)));
        assertThat(book.open("askdb", "dev")).isEmpty();
        server.stop(0);
    }

    @Test void watcherRecordsPodsWithoutClassifyingOrInsertingUnregisteredAgents() {
        var watcher = new K8sAgentWatcher(null, Set.of("askdb"));
        watcher.observe(pod("askdb", "dev", "v1", true), false);
        watcher.observe(pod("ghost", "dev", "v1", true), false);
        assertThat(watcher.live()).extracting(K8sAgentWatcher.LivePod::name).containsExactlyInAnyOrder("askdb", "ghost");
        assertThat(watcher.live().stream().filter(row -> row.name().equals("ghost")).findFirst().orElseThrow().registered()).isFalse();
        assertThat(watcher.live().stream().filter(row -> row.name().equals("askdb")).findFirst().orElseThrow().registered()).isTrue();
    }

    @Test void langfuseMetricsAreNotCalledAgainWithinADay() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            var body = "{\"data\":[{\"count_count\":\"0\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var base = "http://127.0.0.1:" + server.getAddress().getPort();
        var probe = new TrafficProbe(new LangfuseClient(base, "pk", "sk"), new LiteLlmClient("http://127.0.0.1:1", ""));
        assertThat(probe.langfuse(NOW)).isFalse();
        assertThat(probe.langfuse(NOW.plusSeconds(60))).isFalse();
        assertThat(calls.get()).isEqualTo(1);
        server.stop(0);
    }

    private static ReconcileRules.Input input(String status, boolean registered, boolean running, boolean ready,
                                              String instanceVersion, String registeredVersion,
                                              Boolean langfuse, Boolean gateway) {
        return new ReconcileRules.Input(status, registered, running, ready, NOW, NOW, instanceVersion, registeredVersion, langfuse, gateway);
    }

    private static io.fabric8.kubernetes.api.model.Pod pod(String name, String env, String version, boolean ready) {
        return new PodBuilder()
                .withNewMetadata().withName(name + "-0").withUid(name + "-uid")
                .addToLabels("keel.io/agent", name)
                .addToLabels("keel.io/env", env)
                .addToLabels("keel.io/version", version)
                .endMetadata()
                .withNewStatus()
                .addNewCondition().withType("Ready").withStatus(ready ? "True" : "False").endCondition()
                .endStatus()
                .build();
    }
}
