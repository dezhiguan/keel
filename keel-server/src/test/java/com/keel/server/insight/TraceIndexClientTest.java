package com.keel.server.insight;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TraceIndexClientTest {
    @Test void fetchesExactlyOneCheckpointablePageAndOnlyRootsRequestInputOutput() throws Exception {
        var requests=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8));var bytes="{\"data\":[],\"meta\":{\"cursor\":\"next opaque\"}}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.start();
        try {
            var client=new LangfuseClient("http://127.0.0.1:"+server.getAddress().getPort(),"project","secret");
            var from=Instant.parse("2026-10-20T00:00:00Z");var to=from.plusSeconds(3600);
            var first=client.observationIndexPage(from,to,true,null);
            assertThat(first.path("meta").path("cursor").asText()).isEqualTo("next opaque");assertThat(requests).hasSize(1);
            client.observationIndexPage(from,to,false,"next opaque");assertThat(requests).hasSize(2);
            assertThat(requests.getFirst()).contains("isRootObservation=true","fields=core,basic,io,metadata,model,usage","fromStartTime="+from,"toStartTime="+to);
            assertThat(requests.getLast()).contains("cursor=next opaque","fields=core,basic,metadata,model,usage").doesNotContain("isRootObservation=true",",io,");
        } finally {server.stop(0);}
    }
}
