package com.keel.server.insight;

import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalQueryServiceTest {

    @Test void latestReadsExperimentsAndNotTheRemovedTraceApi() throws Exception {
        var paths = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            paths.add(path);
            String body = path.contains("experiment-items")
                    ? """
                    {"data":[
                      {"scores":[{"name":"安全规程合规","value":0.80},{"name":"引用接地","value":0.91}]}
                    ]}
                    """
                    : """
                    {"data":[
                      {"id":"exp-1","name":"askdb-candidate","datasetName":"askdb/smoke","createdAt":"2026-10-05T01:00:00Z",
                       "scores":[{"name":"安全规程合规","value":0.86},{"name":"引用接地","value":0.90}]}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var service = new EvalQueryService(new LangfuseClient("http://127.0.0.1:" + server.getAddress().getPort(), "pk", "sk"), name -> null);
        var result = service.latest("askdb");
        assertThat(paths).contains("/api/public/experiments");
        assertThat(paths).noneMatch(path -> path.contains("/api/public/traces"));
        assertThat(result.get("dataset")).isEqualTo("askdb/smoke");
        @SuppressWarnings("unchecked")
        var dimensions = (List<Map<String, Object>>) result.get("dimensions");
        assertThat(dimensions).anySatisfy(row -> {
            assertThat(row.get("tag")).isEqualTo("安全规程合规");
            assertThat(row.get("candidateScore")).isEqualTo(0.80);
            assertThat(row.get("prodScore")).isEqualTo(0.86);
        });
        server.stop(0);
    }

    @Test void overviewUsesTheSameExperimentScoreAsTheEvalPage() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            String body = path.contains("experiment-items")
                    ? """
                    {"data":[
                      {"scores":[{"name":"格式正确","value":0.90},{"name":"回答是否回声","value":0.93}]}
                    ]}
                    """
                    : """
                    {"data":[
                      {"id":"exp-echo","name":"echo-smoke-20261005","datasetName":"echo/smoke","createdAt":"2026-10-05T12:00:00Z",
                       "scores":[{"name":"格式正确","value":0.91},{"name":"回答是否回声","value":0.88}]}
                    ]}
                    """;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var service = new EvalQueryService(new LangfuseClient("http://127.0.0.1:" + server.getAddress().getPort(), "pk", "sk"), name -> null);
        assertThat(service.latestScoreByAgent().get("echo")).isEqualTo(0.915);
        server.stop(0);
    }

    @Test void missingExperimentIsNotFound() {
        var service = new EvalQueryService(new LangfuseClient("", "", ""), name -> null);
        assertThatThrownBy(() -> service.latest("askdb")).isInstanceOf(KeelException.class);
    }
}
