package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalProvisionerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void convertsDailyCnyUsingTheConfiguredRateAndBlocksOnRevoke() throws Exception {
        var bodies = new ArrayList<String>();
        var paths = new ArrayList<String>();
        var base = server((exchange, body) -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(body);
            var response = exchange.getRequestURI().getPath().equals("/key/generate")
                    ? "{\"key\":\"keel-virtual-key\"}" : "";
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        });
        var secrets = secretWriter(null);
        var provisioner = new LiteLlmProvisioner(new LiteLlmClient(base, "master"), secrets, "7");
        var manifest = json.readTree("""
                {"spec":{"models":{"default":"qwen-plus","fallback":["deepseek-v3"],"budget":{"dailyCny":30}}}}
                """);
        provisioner.provision("code-review", "dev", manifest);
        provisioner.revoke("code-review", "dev");
        var generated = json.readTree(bodies.get(0));
        assertThat(generated.get("max_budget").decimalValue()).isEqualByComparingTo("4.28571429");
        assertThat(generated.get("max_budget").asDouble()).isNotEqualTo(30);
        assertThat(generated.get("key_alias").asText()).isEqualTo("code-review-dev");
        assertThat(generated.get("budget_duration").asText()).isEqualTo("1d");
        assertThat(bodies.get(0)).doesNotContain("langfuse", "success_callback");
        assertThat(paths).containsExactly("/key/generate", "/key/block");
        assertThat(secrets.privateKeyFor("code-review")).isNull();
    }

    @Test void missingExchangeRateFailsBeforeAnyCall() {
        var calls = new AtomicInteger();
        var provisioner = new LiteLlmProvisioner(new LiteLlmClient("http://127.0.0.1:1", "master"), secretWriter(null), "");
        assertThatThrownBy(() -> provisioner.provision("code-review", "dev", json.readTree("""
                {"spec":{"models":{"budget":{"dailyCny":30}}}}
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KEEL_USD_CNY_RATE");
        assertThat(calls.get()).isZero();
    }

    @Test void upstreamFailureIsNotSwallowed() throws Exception {
        var base = server((exchange, body) -> exchange.sendResponseHeaders(500, -1));
        var provisioner = new LiteLlmProvisioner(new LiteLlmClient(base, "master"), secretWriter(null), "7");
        assertThatThrownBy(() -> provisioner.provision("code-review", "dev", json.readTree("""
                {"spec":{"models":{"default":"qwen-plus","budget":{"dailyCny":30}}}}
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("500");
    }

    @Test void importingTheSameSeedTwiceDoesNotDuplicateItems() throws Exception {
        var items = ConcurrentHashMap.<String>newKeySet();
        var creates = new AtomicInteger();
        var base = server((exchange, body) -> {
            var path = exchange.getRequestURI().getPath();
            if (path.equals("/api/public/dataset-items") && "POST".equals(exchange.getRequestMethod())) {
                creates.incrementAndGet();
                items.add(json.readTree(body).path("id").asText());
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (path.startsWith("/api/public/dataset-items")) {
                var data = items.stream().map(id -> "{\"id\":\"" + id + "\"}").reduce((a, b) -> a + "," + b).orElse("");
                var response = ("{\"data\":[" + data + "]}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                return;
            }
            exchange.sendResponseHeaders(200, -1);
        });
        var seed = Files.createTempFile("seed", ".jsonl");
        Files.writeString(seed, """
                {"id":"case-1","input":"风机","expectedOutput":"停机"}
                {"id":"case-2","input":"风扇","expectedOutput":"更换"}
                """);
        var provisioner = new LangfuseProvisioner(new LangfuseClient(base, "pk-lf", "sk-lf"), seed.toString());
        var manifest = json.readTree("{\"spec\":{\"eval\":{\"dataset\":\"code-review/findings\"}}}");
        provisioner.provision("code-review", "dev", manifest);
        provisioner.provision("code-review", "dev", manifest);
        assertThat(items).containsExactlyInAnyOrder("case-1", "case-2");
        assertThat(creates.get()).isEqualTo(2);
    }

    @Test void secretContainsTheSixKeysAndNoVendorCredential() throws Exception {
        var http = new okhttp3.mockwebserver.MockWebServer();
        http.start();
        var created = "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\"keel-code-review\",\"namespace\":\"agents\"}}";
        http.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(201)
                .setHeader("Content-Type", "application/json").setBody(created));
        var kubeconfig = Files.createTempFile("kube", ".yaml");
        Files.writeString(kubeconfig, "apiVersion: v1\nkind: Config\nclusters: []\nusers: []\ncontexts: []\n");
        var previous = System.setProperty("kubeconfig", kubeconfig.toString());
        var config = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withMasterUrl(http.url("/").toString())
                .withTrustCerts(true)
                .withNamespace("agents")
                .build();
        if (previous == null) {
            System.clearProperty("kubeconfig");
        } else {
            System.setProperty("kubeconfig", previous);
        }
        try (var client = new io.fabric8.kubernetes.client.KubernetesClientBuilder().withConfig(config).build()) {
            var secrets = secretWriter(client);
            secrets.rememberPrivateKey("code-review", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n");
            secrets.rememberLlmKey("code-review", "keel-virtual-key");
            secrets.provision("code-review", "dev", json.createObjectNode());
            var posted = new StringBuilder();
            for (var i = 0; i < 4; i++) {
                var request = http.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS);
                if (request == null) {
                    break;
                }
                if ("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod())) {
                    posted.append(request.getBody().readUtf8());
                }
            }
            var body = json.readTree(posted.toString());
            var values = new java.util.HashMap<String, String>();
            body.path("data").fields().forEachRemaining(entry -> values.put(entry.getKey(),
                    new String(Base64.getDecoder().decode(entry.getValue().asText()), StandardCharsets.UTF_8)));
            assertThat(values.keySet()).containsExactlyInAnyOrder(
                    "KEEL_CLIENT_PRIVATE_KEY", "KEEL_LLM_KEY", "KEEL_LLM_BASE_URL",
                    "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY", "LANGFUSE_HOST");
            assertThat(values.get("KEEL_LLM_KEY")).isEqualTo("keel-virtual-key");
            assertThat(String.join("\n", values.values())).doesNotContain("DASHSCOPE", "DEEPSEEK_API_KEY");
        } finally {
            http.shutdown();
        }
    }

    private SecretWriter secretWriter(io.fabric8.kubernetes.client.KubernetesClient client) {
        return new SecretWriter(client, "pk-lf", "sk-lf", "http://langfuse", "http://litellm");
    }

    private String server(Handler handler) throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            try {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                handler.handle(exchange, body);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        http.start();
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @FunctionalInterface
    interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange, String body) throws Exception;
    }
}
