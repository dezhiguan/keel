package com.keel.server.integration.langfuse;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangfuseSeedReuseTest {
    @Test void populatedDatasetCanBeReusedWithoutASeedFile() throws Exception {
        check("{\"data\":[{\"id\":\"case-1\"}]}", true);
    }

    @Test void emptyDatasetStillRequiresASeedFile() throws Exception {
        check("{\"data\":[]}", false);
    }

    private void check(String items, boolean populated) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/public/datasets", exchange -> {
            var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/api/public/dataset-items", exchange -> {
            var body = items.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new LangfuseClient("http://127.0.0.1:" + server.getAddress().getPort(), "public", "secret");
            var absent = Path.of("missing-eval-seed-1016.jsonl");
            if (populated) assertThatCode(() -> client.importSeed("qa/smoke", absent)).doesNotThrowAnyException();
            else assertThatThrownBy(() -> client.importSeed("qa/smoke", absent))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("找不到评测用例");
        } finally {
            server.stop(0);
        }
    }
}
