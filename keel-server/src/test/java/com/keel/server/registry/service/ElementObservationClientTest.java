package com.keel.server.registry.service;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ElementObservationClientTest {
    @Test void evidenceReadsOneMetadataPageAndNeverFollowsCursorOrFetchesUserContent() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var count=new AtomicInteger();
        server.createContext("/api/public/v2/observations",exchange->{
            count.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).contains("limit=1000","fields=core,basic,metadata","fromStartTime=").doesNotContain("fields=core,basic,io");
            var bytes="{\"data\":[],\"meta\":{\"cursor\":\"another-page\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var client=new LangfuseClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-public","test-secret");
            assertThat(client.elementObservations(Instant.now()).path("data").isArray()).isTrue();
            assertThat(count.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
}
