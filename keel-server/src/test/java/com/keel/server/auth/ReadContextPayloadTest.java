package com.keel.server.auth;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.agent.AgentEndpointClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ReadContextPayloadTest {
    @Test void trustedCapabilityAndEvaluationIsolationTravelOutsideModelQuestion() throws Exception {
        var received=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();var evals=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/invoke",exchange->{
            received.add(new ObjectMapper().readTree(exchange.getRequestBody()));evals.add(exchange.getRequestHeaders().getFirst("X-Keel-Eval-Run"));
            byte[] body="event: final\ndata: {}\n\n".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try(var stream=new AgentEndpointClient().openStream("http://127.0.0.1:"+server.getAddress().getPort(),"员工列表",null,"assistant","staging","54",null,null,"scoped-session",List.of(),"fixture-read-context","v02-evaluation")){
            stream.readAllBytes();
            assertThat(received.getFirst().path("input").path("text").asText()).isEqualTo("员工列表");
            assertThat(received.getFirst().path("input").path("_keel_read_context").asText()).isEqualTo("fixture-read-context");
            assertThat(received.getFirst().path("context").path("session_messages").isEmpty()).isTrue();
            assertThat(evals).containsExactly("v02-evaluation");
        }finally{server.stop(0);}
    }
}
