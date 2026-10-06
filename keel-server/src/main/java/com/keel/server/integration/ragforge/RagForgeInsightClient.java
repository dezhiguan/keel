package com.keel.server.integration.ragforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Reads rag-forge actuator endpoints. A failed read is null, not a fake healthy value. */
public class RagForgeInsightClient {
    private final String baseUrl;
    private final String authorization;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public RagForgeInsightClient(String baseUrl, String user, String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            this.authorization = "";
        } else {
            this.authorization = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }

    public JsonNode insight() {
        if (authorization.isBlank()) {
            return null;
        }
        return getJson(baseUrl + "/actuator/keel");
    }

    /** Retrieval-eval summary for one knowledge base. Null when rag-forge has no score for it. */
    public JsonNode evalSummary(String kb) {
        if (authorization.isBlank() || kb == null || kb.isBlank()) {
            return null;
        }
        return getJson(baseUrl + "/api/v1/eval/summary?kb=" + URLEncoder.encode(kb, StandardCharsets.UTF_8));
    }

    public boolean up() {
        if (baseUrl.isBlank()) {
            return false;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 300 && response.body().contains("\"UP\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** One exposition body per reachable API pod. Falls back to the Service address. */
    public Scrape prometheus() {
        if (authorization.isBlank()) {
            return new Scrape(List.of(), 0);
        }
        var targets = endpoints();
        boolean discovered = !targets.isEmpty();
        if (!discovered) {
            targets = List.of(baseUrl);
        }
        var bodies = new ArrayList<String>();
        for (String target : targets) {
            String body = getText(target + "/actuator/prometheus");
            if (body != null) {
                bodies.add(body);
            }
        }
        return new Scrape(bodies, discovered ? targets.size() : 0);
    }

    private List<String> endpoints() {
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        if (host == null || host.isBlank()) {
            return List.of();
        }
        Path tokenFile = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
        if (!Files.isRegularFile(tokenFile)) {
            return List.of();
        }
        try {
            String token = Files.readString(tokenFile).trim();
            var request = HttpRequest.newBuilder(URI.create(
                            "https://kubernetes.default.svc/api/v1/namespaces/ragforge/endpoints/ragforge-backend"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            var response = kube().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return List.of();
            }
            var addresses = new ArrayList<String>();
            json.readTree(response.body()).path("subsets").forEach(subset -> {
                int subsetPort = subset.path("ports").path(0).path("port").asInt(8080);
                subset.path("addresses").forEach(address -> {
                    String ip = address.path("ip").asText("");
                    if (!ip.isBlank()) {
                        addresses.add("http://" + ip + ":" + subsetPort);
                    }
                });
            });
            return addresses;
        } catch (Exception e) {
            return List.of();
        }
    }

    private HttpClient kube() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate certificate;
        try (InputStream in = Files.newInputStream(Path.of("/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"))) {
            certificate = factory.generateCertificate(in);
        }
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        store.setCertificateEntry("cluster", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(2)).build();
    }

    private JsonNode getJson(String url) {
        String body = getText(url);
        if (body == null) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String getText(String url) {
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Authorization", authorization)
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return null;
            }
            return response.body();
        } catch (Exception e) {
            return null;
        }
    }

    public record Scrape(List<String> bodies, int targets) {}
}
