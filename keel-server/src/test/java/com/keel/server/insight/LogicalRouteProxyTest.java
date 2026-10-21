package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.common.KeelException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class LogicalRouteProxyTest {
    @Test void preservesDraftRevisionErrorDetailsAndAuditsWritesButNotReplay() throws Exception {
        var requests=new ArrayList<String>();var audits=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/admin/v1/route-table",e->{
            var raw=new String(e.getRequestBody().readAllBytes());requests.add(e.getRequestMethod()+" "+e.getRequestURI()+" "+raw);
            boolean invalid=raw.contains("999");
            var text=invalid?"{\"message\":\"qa-key-staging 缺 deepseek-flash\"}":"{\"revision\":7,\"version\":3,\"rules\":[],\"draft\":[],\"gaps\":[]}";
            var bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(invalid?400:200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try {
            var costs=new CostService(new LiteLlmClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-admin"),new LangfuseClient("","",""),(a,e)->audits.incrementAndGet());
            assertThat(costs.logicalRouteTable(null,Map.of()).path("revision").asInt()).isEqualTo(7);
            costs.logicalRouteTable("",Map.of("revision",6,"rules",List.of()));
            costs.logicalRouteTable("publish",Map.of("revision",7));
            costs.logicalRouteTable("replay",Map.of("revision",7));
            assertThat(audits).hasValue(2);
            assertThatThrownBy(()->costs.logicalRouteTable("publish",Map.of("revision",999))).isInstanceOf(KeelException.class).hasMessageContaining("qa-key-staging");
            assertThat(requests).anyMatch(r->r.contains("/route-table/replay") && r.contains("\"revision\":7"));
        }finally{server.stop(0);}
    }
}
