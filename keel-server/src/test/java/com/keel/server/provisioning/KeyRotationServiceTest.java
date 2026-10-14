package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.ConfigBuilder;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyRotationServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void auditFailureStopsRotationBeforeGatewayCall() {
        var writer = new SecretWriter(null, "", "", "", "") {
            @Override public RotationTarget rotationTarget(String agent, String env) {
                return new RotationTarget("agents", agent);
            }
        };
        var service = new KeyRotationService(new LiteLlmClient("http://127.0.0.1:1", "test-admin"), writer,
                (agent, env) -> { throw new IllegalStateException("audit unavailable"); });
        assertThatThrownBy(() -> service.rotate("echo-dev"))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
    }

    @Test void rotationCopiesCurrentPolicyUpdatesSecretAndRestartsDeployment() throws Exception {
        var gatewayCalls = new ArrayList<String>();
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            gatewayCalls.add(exchange.getRequestMethod() + " " + path);
            var response = path.endsWith("/echo-dev")
                    ? "{\"alias\":\"echo-dev\",\"models\":[\"qwen-plus\"],\"fallback\":[],\"dailyBudgetCny\":30,\"allowFallback\":false,\"blocked\":false,\"version\":1}"
                    : "{\"alias\":\"echo-dev\",\"key\":\"virtual-test-next\",\"version\":2}";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        gateway.start();

        var old = Base64.getEncoder().encodeToString("virtual-test-old".getBytes(StandardCharsets.UTF_8));
        var secret = new AtomicReference<>("""
                {"apiVersion":"v1","kind":"Secret","metadata":{"name":"keel-echo","namespace":"agents","resourceVersion":"1"},"data":{"KEEL_LLM_KEY":"%s","KEEP":"a2VlcA=="}}
                """.formatted(old));
        var deployment = new AtomicReference<>("""
                {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"echo","namespace":"agents","resourceVersion":"1"},"spec":{"template":{"metadata":{"labels":{"app":"echo"}},"spec":{"containers":[{"name":"echo","image":"example.invalid/echo:test","envFrom":[{"secretRef":{"name":"keel-echo"}}]}]}}}}
                """);
        var writes = new ArrayList<String>();
        var kube = new MockWebServer();
        kube.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                var path = request.getPath();
                if (path == null) return response(404, "{}");
                if (path.endsWith("/secrets/keel-echo")) {
                    if ("PUT".equals(request.getMethod())) {
                        secret.set(request.getBody().readUtf8());
                        writes.add("secret");
                    }
                    return response(200, secret.get());
                }
                if (path.endsWith("/deployments/echo")) {
                    if ("PUT".equals(request.getMethod())) {
                        deployment.set(request.getBody().readUtf8());
                        writes.add("deployment");
                    }
                    return response(200, deployment.get());
                }
                return response(404, "{}");
            }
        });
        kube.start();
        var audit = new AtomicReference<String>();
        var kubeconfig = java.nio.file.Files.createTempFile("kube", ".yaml");
        java.nio.file.Files.writeString(kubeconfig, "apiVersion: v1\nkind: Config\nclusters: []\nusers: []\ncontexts: []\n");
        var previous = System.setProperty("kubeconfig", kubeconfig.toString());
        var config = new ConfigBuilder().withMasterUrl(kube.url("/").toString()).withNamespace("agents").build();
        if (previous == null) System.clearProperty("kubeconfig");
        else System.setProperty("kubeconfig", previous);
        try (var client = new KubernetesClientBuilder().withConfig(config).build()) {
            var writer = new SecretWriter(client, "", "", "", "");
            var service = new KeyRotationService(new LiteLlmClient("http://127.0.0.1:" + gateway.getAddress().getPort(), "test-admin"),
                    writer, (agent, env) -> audit.set(agent + ":" + env));
            var result = service.rotate("echo-dev");
            assertThat(result.version()).isEqualTo(2);
            assertThat(result.key()).isEqualTo("virtual-test-next");
            assertThat(gatewayCalls).containsExactly("GET /admin/v1/keys/echo-dev", "POST /admin/v1/keys");
            assertThat(writes).containsExactly("secret", "deployment");
            assertThat(json.readTree(secret.get()).path("data").path("KEEL_LLM_KEY").asText())
                    .isEqualTo(Base64.getEncoder().encodeToString("virtual-test-next".getBytes(StandardCharsets.UTF_8)));
            assertThat(json.readTree(secret.get()).path("data").path("KEEP").asText()).isEqualTo("a2VlcA==");
            assertThat(json.readTree(deployment.get()).path("spec").path("template").path("metadata")
                    .path("annotations").path("keel.io/key-rotated-at").asText()).isNotBlank();
            assertThat(audit.get()).isEqualTo("echo:dev");
        } finally {
            kube.shutdown();
            gateway.stop(0);
        }
    }

    private static MockResponse response(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }
}
