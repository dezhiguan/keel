package com.keel.server.integration.audit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RuntimeAuditQueryTest {
    @Test void mergesChronologicallyPaginatesAndPreservesBothSources() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/audit/events",exchange->{
            byte[] body="""
                    {"code":"OK","data":{"total":1,"items":[{"event_id":"sdk","ts":"2026-10-25T11:00:00Z",
                    "agent":"careermate-keel","env":"staging","trace_id":"trace","risk":"high","action":"tool.call",
                    "decision":"allowed","payload":{},"hashVerified":true,"hash":"actual-hash"}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var store=new AuditStore() {
                public void append(String a,String e,String act,String r,String d,String resource,String trace) {}
                public Map<String,Object> verify(String agent) { return Map.of("intact",true,"checked",1); }
                public Map<String,Object> page(String a,String r,String e,int p,int size) {
                    return Map.of("total",1,"items",List.of(Map.of("eventId","console","ts","2026-10-25T10:00:00Z","action","invoke")));
                }
            };
            var query=new RuntimeAuditQuery(store,"http://127.0.0.1:"+server.getAddress().getPort());
            var first=query.page("careermate-keel",null,"staging",null,null,1,1);
            assertThat(first.get("total")).isEqualTo(2);
            var row=(Map<?,?>)((List<?>)first.get("items")).getFirst();
            assertThat(row.get("eventId")).isEqualTo("sdk");
            assertThat(row.get("traceId")).isEqualTo("trace");
            assertThat(row.get("risk")).isEqualTo("HIGH");
            assertThat(row.get("hashVerified")).isEqualTo(true);
            var second=query.page("careermate-keel",null,"staging",null,null,2,1);
            assertThat(((Map<?,?>)((List<?>)second.get("items")).getFirst()).get("eventId")).isEqualTo("console");
            assertThatThrownBy(()->query.page(null,null,"all",null,null,0,10)).isInstanceOf(IllegalArgumentException.class);
        }finally{server.stop(0);}
    }
}
