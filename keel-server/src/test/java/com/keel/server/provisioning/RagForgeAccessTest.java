package com.keel.server.provisioning;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagForgeAccessTest {
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test void issuesAReadKeyForTheSelectedBasesAndRevokesIt() throws Exception {
        var created = new AtomicReference<String>();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response;
            if ("GET".equals(exchange.getRequestMethod()) && "/api/v1/kb".equals(path)) {
                response = "{\"code\":200,\"data\":[{\"id\":7,\"name\":\"demo\"},{\"id\":8,\"name\":\"other\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if ("POST".equals(exchange.getRequestMethod()) && "/api/v1/keys".equals(path)) {
                created.set(body);
                response = "{\"code\":200,\"data\":{\"id\":41,\"apiKey\":\"sk-rf-test\"}}".getBytes(StandardCharsets.UTF_8);
            } else if ("DELETE".equals(exchange.getRequestMethod()) && "/api/v1/keys/41".equals(path)) {
                response = "{}".getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        http.start();
        try {
            var request = new MockHttpServletRequest();
            request.setCookies(new Cookie("keel_console_at", "console-token"));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var access = new RagForgeAccess("http://127.0.0.1:" + http.getAddress().getPort());
            var issued = access.issue("qa-agent", "dev", List.of("demo"));
            assertThat(issued.id()).isEqualTo(41);
            assertThat(issued.token()).isEqualTo("sk-rf-test");
            assertThat(created.get()).contains("\"scopeMode\":\"KB_LIST\"", "\"accessLevel\":\"READ\"", "\"allowedKbIds\":[7]");
            access.revoke(issued.id());
            assertThatThrownBy(() -> access.issue("qa-agent", "dev", List.of("hidden")))
                    .hasMessageContaining("hidden");
        } finally {
            http.stop(0);
        }
    }

    @Test void refusesWhenTheConsoleTokenIsMissing() {
        assertThatThrownBy(() -> new RagForgeAccess("http://127.0.0.1:1").issue("qa", "dev", List.of("demo")))
                .hasMessageContaining("凭证");
    }
}
