package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EchoCredentialWriterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void missingGatewayAliasReplacesTheStoredKey() throws Exception {
        var creates = new AtomicInteger();
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var method = exchange.getRequestMethod();
            byte[] body;
            int status;
            if ("GET".equals(method) && path.equals("/admin/v1/keys/echo-dev")) {
                body = "{\"code\":\"SERVER_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8);
                status = 404;
            } else if ("POST".equals(method) && path.equals("/admin/v1/keys")) {
                creates.incrementAndGet();
                body = "{\"alias\":\"echo-dev\",\"key\":\"sk-keel-fresh\"}".getBytes(StandardCharsets.UTF_8);
                status = 201;
            } else {
                body = new byte[0];
                status = 500;
            }
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gateway.start();

        var posted = new ArrayList<String>();
        var kube = new MockWebServer();
        var existing = """
                {"apiVersion":"v1","kind":"Secret","metadata":{"name":"keel-echo","namespace":"keel-system","resourceVersion":"1"},"data":{"KEEL_LLM_KEY":"%s"}}
                """.formatted(Base64.getEncoder().encodeToString("sk-keel-stale".getBytes(StandardCharsets.UTF_8)));
        kube.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) {
                    posted.add(request.getBody().readUtf8());
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(existing);
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(existing);
            }
        });
        kube.start();
        var kubeconfig = java.nio.file.Files.createTempFile("kube", ".yaml");
        java.nio.file.Files.writeString(kubeconfig, "apiVersion: v1\nkind: Config\nclusters: []\nusers: []\ncontexts: []\n");
        var previous = System.setProperty("kubeconfig", kubeconfig.toString());
        var config = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withMasterUrl(kube.url("/").toString())
                .withTrustCerts(true)
                .withNamespace("keel-system")
                .build();
        if (previous == null) {
            System.clearProperty("kubeconfig");
        } else {
            System.setProperty("kubeconfig", previous);
        }
        try (var client = new KubernetesClientBuilder().withConfig(config).build()) {
            var writer = new EchoCredentialWriter(client,
                    new LiteLlmClient("http://127.0.0.1:" + gateway.getAddress().getPort(), "admin"));
            writer.ensure("echo", "http://gateway", "admin");
        } finally {
            gateway.stop(0);
            kube.shutdown();
        }
        assertThat(creates.get()).isEqualTo(1);
        assertThat(posted).isNotEmpty();
        var values = new java.util.HashMap<String, String>();
        json.readTree(posted.get(0)).path("data").fields().forEachRemaining(entry ->
                values.put(entry.getKey(), new String(Base64.getDecoder().decode(entry.getValue().asText()), StandardCharsets.UTF_8)));
        assertThat(values.get("KEEL_LLM_KEY")).isEqualTo("sk-keel-fresh");
    }

    @Test void existingAliasIsLeftAlone() throws Exception {
        var creates = new AtomicInteger();
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                creates.incrementAndGet();
            }
            var body = "{\"alias\":\"echo-dev\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gateway.start();
        var writes = new AtomicInteger();
        var kube = new MockWebServer();
        var existing = """
                {"apiVersion":"v1","kind":"Secret","metadata":{"name":"keel-echo","namespace":"keel-system","resourceVersion":"1"},"data":{"KEEL_LLM_KEY":"%s"}}
                """.formatted(Base64.getEncoder().encodeToString("sk-keel-live".getBytes(StandardCharsets.UTF_8)));
        kube.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) {
                    writes.incrementAndGet();
                }
                return new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(existing);
            }
        });
        kube.start();
        var kubeconfig = java.nio.file.Files.createTempFile("kube", ".yaml");
        java.nio.file.Files.writeString(kubeconfig, "apiVersion: v1\nkind: Config\nclusters: []\nusers: []\ncontexts: []\n");
        var previous = System.setProperty("kubeconfig", kubeconfig.toString());
        var config = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withMasterUrl(kube.url("/").toString())
                .withTrustCerts(true)
                .withNamespace("keel-system")
                .build();
        if (previous == null) {
            System.clearProperty("kubeconfig");
        } else {
            System.setProperty("kubeconfig", previous);
        }
        try (var client = new KubernetesClientBuilder().withConfig(config).build()) {
            var writer = new EchoCredentialWriter(client,
                    new LiteLlmClient("http://127.0.0.1:" + gateway.getAddress().getPort(), "admin"));
            writer.ensure("echo", "http://gateway", "admin");
        } finally {
            gateway.stop(0);
            kube.shutdown();
        }
        assertThat(creates.get()).isZero();
        assertThat(writes.get()).isZero();
    }
}
