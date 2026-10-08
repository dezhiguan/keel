package com.keel.server.prompt;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PromptServiceCacheTest {
    @Test void repeatedListReadDoesNotCallLangfuseAgain() throws Exception {
        var reads = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/v2/prompts", exchange -> {
            reads.incrementAndGet();
            var bytes = "{\"data\":[],\"meta\":{\"totalPages\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var catalog = new PromptCatalog(null) {
                @Override public List<Item> declared(String agentFilter) {
                    return List.of();
                }
            };
            var service = new PromptService(catalog, new LangfuseClient(base, "pk", "sk"),
                    null, null, base, "project");
            service.list("all", null);
            service.list("all", null);
            assertThat(reads).hasValue(1);
            service.list("dev", null);
            assertThat(reads).hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
