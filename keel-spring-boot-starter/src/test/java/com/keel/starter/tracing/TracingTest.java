package com.keel.starter.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

class TracingTest {
    private static final String SECRET = "SECRET USER TEXT";

    @Test
    void langfuseExportUsesOtlpHttpAndIngestionVersion() throws Exception {
        List<com.sun.net.httpserver.HttpExchange> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SpanExporter delegate = LangfuseSpanExporter.create("http://127.0.0.1:" + port, "pk-test", "sk-test");
            SpanExporter exporter = new SpanExporter() {
                @Override public io.opentelemetry.sdk.common.CompletableResultCode export(
                        java.util.Collection<io.opentelemetry.sdk.trace.data.SpanData> spans) {
                    var code = delegate.export(spans);
                    code.join(3, java.util.concurrent.TimeUnit.SECONDS);
                    if (!code.isSuccess()) throw new AssertionError("export failed", code.getFailureThrowable());
                    return code;
                }
                @Override public io.opentelemetry.sdk.common.CompletableResultCode flush() { return delegate.flush(); }
                @Override public io.opentelemetry.sdk.common.CompletableResultCode shutdown() { return delegate.shutdown(); }
            };
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
            Tracer tracer = provider.get("test");
            tracer.spanBuilder("agent.run").startSpan().end();
            provider.forceFlush();
            assertFalse(seen.isEmpty(), "exporter did not call the fake Langfuse server");
            var headers = seen.get(0).getRequestHeaders();
            assertEquals("/api/public/otel/v1/traces", seen.get(0).getRequestURI().getPath());
            assertEquals("4", firstHeader(headers, LangfuseSpanExporter.INGESTION_VERSION_HEADER));
            assertTrue(firstHeader(headers, "Authorization").startsWith("Basic "));
            assertTrue(firstHeader(headers, "Content-Type").contains("protobuf"));
            provider.close();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourcesDoNotReferenceGrpcExporter() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("OtlpGrpcSpanExporter")) {
                    fail(file + " references the gRPC exporter");
                }
            }
        }
    }

    @Test
    void observationTreeUsesContractAttributeKeys() throws Exception {
        Set<String> documented = documentedKeys();
        Set<String> constants = new HashSet<>();
        for (var field : SpanAttributes.class.getFields()) constants.add((String) field.get(null));
        assertEquals(documented, constants);

        Recording recording = new Recording();
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(recording).build();
        Tracer tracer = provider.get("test");
        var agent = GenAiSpans.agent(tracer, "alpha", "run-1");
        try (Scope ignored = agent.makeCurrent()) {
            var generation = GenAiSpans.generation(tracer, "alpha", "run-1", "qwen");
            GenAiSpans.recordUsage(generation, 3, 5);
            generation.end();
            GenAiSpans.tool(tracer, "lookup_resume", "alpha", "run-1").end();
            GenAiSpans.retriever(tracer, "alpha", "run-1").end();
        }
        agent.end();
        provider.forceFlush();
        assertEquals(Set.of("agent", "generation", "tool", "retriever"), types(recording));
        var agentSpan = recording.ended.stream()
                .filter(span -> "agent".equals(String.valueOf(span.getAttributes().get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(SpanAttributes.OBSERVATION_TYPE)))))
                .findFirst().orElseThrow();
        for (var ended : recording.ended) {
            if (ended == agentSpan) continue;
            assertEquals(agentSpan.getSpanContext().getSpanId(), ended.getParentSpanContext().getSpanId());
            for (var key : ended.getAttributes().asMap().keySet()) {
                assertTrue(documented.contains(key.getKey()), key.getKey());
            }
        }
        provider.close();
    }

    @Test
    void virtualThreadLogCarriesTraceIdAndAgent() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("keel.tracing.test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(MdcKeys.TRACE_ID_SNAKE, "trace-vt");
        MDC.put(MdcKeys.AGENT, "alpha");
        try {
            Thread.ofVirtual().start(MdcContext.wrap(() -> logger.info("from virtual thread"))).join();
            ILoggingEvent event = appender.list.get(0);
            assertEquals("trace-vt", event.getMDCPropertyMap().get("trace_id"));
            assertEquals("alpha", event.getMDCPropertyMap().get("agent"));
        } finally {
            logger.detachAppender(appender);
            MDC.clear();
        }
    }

    @Test
    void sseSpanEndsWhenTheStreamEnds() throws Exception {
        Recording recording = new Recording();
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(recording).build();
        TracingMdcFilter filter = new TracingMdcFilter(new TraceIdResolver(), provider.get("test"), null, "keel-agent");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/invoke");
        request.addHeader("Accept", "text/event-stream");
        request.setContent(SECRET.getBytes(StandardCharsets.UTF_8));
        request.setAsyncSupported(true);
        long started = System.nanoTime();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertFalse(res instanceof ContentCachingResponseWrapper);
            req.startAsync();
        });
        assertTrue(recording.ended.isEmpty());
        Thread.sleep(200);
        request.getAsyncContext().complete();
        assertEquals(1, recording.ended.size());
        long latencyMs = recording.ended.get(0).getLatencyNanos() / 1_000_000;
        assertTrue(latencyMs >= 200, "latencyMs=" + latencyMs + " wall=" + ((System.nanoTime() - started) / 1_000_000));
        assertFalse(recording.ended.get(0).toString().contains(SECRET));
        provider.close();
    }

    @Test
    void mdcKeysAndTraceHeaderMatchCareermate() throws Exception {
        TracingMdcFilter filter = new TracingMdcFilter(new TraceIdResolver(),
                SdkTracerProvider.builder().build().get("test"), null, "keel-agent");
        MDC.put("upstreamKey", "upstreamValue");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.addHeader(MdcKeys.HEADER_REQUEST_ID, "client-req-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            assertTrue(res instanceof ContentCachingResponseWrapper);
            String traceId = MDC.get(MdcKeys.TRACE_ID);
            assertEquals(traceId, MDC.get(MdcKeys.TRACE_ID_SNAKE));
            assertEquals(32, traceId.length());
            assertEquals("keel-agent", MDC.get(MdcKeys.AGENT));
            assertEquals("keel-agent", MDC.get(MdcKeys.SERVICE));
            res.getWriter().write("ok");
        });
        assertEquals("upstreamValue", MDC.get("upstreamKey"));
        assertEquals("client-req-42", response.getHeader(MdcKeys.HEADER_REQUEST_ID));
        assertEquals(32, response.getHeader(MdcKeys.HEADER_TRACE_ID).length());
        assertEquals("ok", response.getContentAsString());
        MDC.clear();
    }

    private static String firstHeader(com.sun.net.httpserver.Headers headers, String name) {
        AtomicReference<String> value = new AtomicReference<>();
        headers.forEach((key, values) -> {
            if (key.equalsIgnoreCase(name) && !values.isEmpty()) value.set(values.get(0));
        });
        if (value.get() == null) fail("missing header " + name);
        return value.get();
    }

    private static Set<String> types(Recording recording) {
        Set<String> types = new HashSet<>();
        var key = io.opentelemetry.api.common.AttributeKey.stringKey(SpanAttributes.OBSERVATION_TYPE);
        for (var span : recording.ended) types.add(String.valueOf(span.getAttributes().get(key)));
        return types;
    }

    private static Set<String> documentedKeys() throws Exception {
        String text = Files.readString(Path.of("..", "contracts", "trace-attributes.md"));
        Set<String> keys = new HashSet<>();
        Matcher quoted = Pattern.compile("`((?:langfuse|keel|gen_ai)\\.[a-z0-9_.]+)`").matcher(text);
        while (quoted.find()) keys.add(quoted.group(1));
        Matcher bare = Pattern.compile("(?m)^((?:langfuse|keel|gen_ai)\\.[a-z0-9_.]+)$").matcher(text);
        while (bare.find()) keys.add(bare.group(1));
        return keys;
    }

    private static final class Recording implements io.opentelemetry.sdk.trace.SpanProcessor {
        private final List<io.opentelemetry.sdk.trace.ReadableSpan> ended = new ArrayList<>();

        @Override public void onStart(io.opentelemetry.context.Context context,
                                       io.opentelemetry.sdk.trace.ReadWriteSpan span) {}

        @Override public boolean isStartRequired() { return false; }

        @Override public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {
            synchronized (ended) { ended.add(span); }
        }

        @Override public boolean isEndRequired() { return true; }
    }
}
