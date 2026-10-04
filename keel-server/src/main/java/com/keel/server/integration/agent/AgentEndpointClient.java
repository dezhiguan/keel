package com.keel.server.integration.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls an agent's own /v1/health and /v1/manifest. No Dify management API. */
public class AgentEndpointClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public boolean healthy(String endpoint) {
        var response = send(endpoint, "/v1/health");
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    public String manifestVersion(String endpoint) {
        var response = send(endpoint, "/v1/manifest");
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("manifest " + response.statusCode());
        }
        try {
            return json.readTree(response.body()).path("version").asText("");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> send(String endpoint, String path) {
        var base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        try {
            var request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
