package com.keel.server.integration.prometheus;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Reads Prometheus. A failed query returns null instead of a fake healthy value. */
public class PrometheusClient {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public PrometheusClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
    }

    public Double query(String promql) {
        if (baseUrl.isBlank()) {
            return null;
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                return null;
            }
            var value = json.readTree(response.body()).path("data").path("result");
            if (!value.isArray() || value.isEmpty()) {
                return null;
            }
            return Double.valueOf(value.get(0).path("value").path(1).asText());
        } catch (Exception e) {
            return null;
        }
    }
}
