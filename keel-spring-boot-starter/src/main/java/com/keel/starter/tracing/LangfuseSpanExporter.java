package com.keel.starter.tracing;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** OTLP/HTTP exporter for Langfuse v4. gRPC is not supported by that ingestion endpoint. */
public final class LangfuseSpanExporter {
    static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";
    static final String INGESTION_VERSION = "4";

    private LangfuseSpanExporter() {}

    public static SpanExporter create(String host, String publicKey, String secretKey) {
        String endpoint = host.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
        String basic = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", "Basic " + basic)
                .addHeader(INGESTION_VERSION_HEADER, INGESTION_VERSION)
                .build();
    }

    /** Missing Langfuse settings mean no export. There is no localhost default. */
    public static SpanExporter fromEnvironment() {
        String host = System.getenv("LANGFUSE_HOST");
        String publicKey = System.getenv("LANGFUSE_PUBLIC_KEY");
        String secretKey = System.getenv("LANGFUSE_SECRET_KEY");
        if (host == null || host.isBlank() || publicKey == null || publicKey.isBlank()
                || secretKey == null || secretKey.isBlank()) {
            return null;
        }
        return create(host, publicKey, secretKey);
    }
}
