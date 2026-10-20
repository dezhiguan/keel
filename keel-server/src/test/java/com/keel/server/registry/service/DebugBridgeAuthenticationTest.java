package com.keel.server.registry.service;

import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.insight.SavedTraces;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;


class DebugBridgeAuthenticationTest {
    @Test void normalAndStreamingDebugAuthenticateTheirOwnAgentOnly() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new java.util.ArrayList<String>();
        server.createContext("/v1/invoke",exchange->{
            received.add(exchange.getRequestHeaders().getFirst("X-Keel-Bridge"));
            var body="event: final\ndata: {\"answer\":\"debug ok\",\"trace_id\":\"debug-trace\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var current = new AgentDetail[1];
            var registry = new AgentRegistryService(null,null,null,null,null,null) {
                @Override public AgentDetail registeredDetail(String name) { return current[0]; }
                @Override public String invokeEndpoint(String name) { return "http://127.0.0.1:"+server.getAddress().getPort(); }
            };
            var audit = new AuditStore() {
                public void append(String a,String e,String action,String risk,String decision,String resource,String trace) {}
                public java.util.Map<String,Object> page(String a,String risk,String env,int page,int size) { return java.util.Map.of(); }
                public java.util.Map<String,Object> verify(String a) { return java.util.Map.of(); }
            };
            var service=new AgentChatService(registry,new AgentEndpointClient(),audit,null,
                    "test-career-identity",SavedTraces.EMPTY,null,null);
            ReflectionTestUtils.setField(service,"metaAgentToken","test-meta-identity");
            for(var name:List.of("meta-agent","careermate","other-agent")) {
                var agent=new Agent();agent.setName(name);agent.setStatus(AgentStatus.ONLINE);agent.setLanguage("python");
                var version=new AgentVersion();version.setEnv("dev");
                current[0] = AgentDetail.of(agent,version,null,List.of(),null,List.of());
                assertThat(service.chat(name,"hello").get("text")).isEqualTo("debug ok");
                var output=new ByteArrayOutputStream();service.streamChat(name,"hello","session","user",false,output);
                assertThat(output.toString(StandardCharsets.UTF_8)).contains("event: final","debug ok");
            }
            assertThat(received).containsExactly("test-meta-identity","test-meta-identity", "test-career-identity","test-career-identity",null,null);
        } finally {server.stop(0);}
    }
}
