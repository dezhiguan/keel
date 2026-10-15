package com.keel.llm;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

public final class Upstream {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public Stream open(String baseUrl, String apiKey, String path, String json, Duration timeout) {
        try {
            var response = http.send(request(baseUrl, apiKey, path, json, timeout).build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Stream(response.statusCode(), response.body(), false);
        } catch (HttpTimeoutException e) {
            return new Stream(504, InputStream.nullInputStream(), true);
        } catch (Exception e) {
            return new Stream(502, InputStream.nullInputStream(), true);
        }
    }

    public Result post(String baseUrl, String apiKey, String path, String json, Duration timeout) {
        try {
            var response = http.send(request(baseUrl, apiKey, path, json, timeout).build(), HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), response.body() == null ? "" : response.body(), false);
        } catch (HttpTimeoutException e) {
            return new Result(504, "", true);
        } catch (Exception e) {
            return new Result(502, "", true);
        }
    }

    private static HttpRequest.Builder request(String baseUrl, String apiKey, String path, String json, Duration timeout) {
        var builder = HttpRequest.newBuilder(URI.create(strip(baseUrl) + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private static String strip(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public record Stream(int status, InputStream body, boolean failed) implements AutoCloseable {
        public boolean retryable() {
            return failed || status >= 500;
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (Exception ignored) {
                // The caller already stopped reading.
            }
        }
    }

    public record Result(int status, String body, boolean failed) {
        public boolean retryable() {
            return failed || status >= 500;
        }
    }
}
