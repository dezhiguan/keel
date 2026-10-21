package com.keel.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class DynamicRoutingTest {
    private static final String Q="qwen3.8-flash",D="deepseek-flash";
    private static RouteTable.Rule rule(String name,int weight,String fallback) {
        return new RouteTable.Rule(name,"staging",Q,D,weight,fallback,0,"",0);
    }
    private static ModelCatalog catalog(int port) {
        return new ModelCatalog(Map.of(Q,new ModelCatalog.Model(Q,new BigDecimal("0.000001"),new BigDecimal("0.000002"),"http://localhost:"+port+"/q",""),
                D,new ModelCatalog.Model(D,new BigDecimal("0.000002"),new BigDecimal("0.000003"),"http://localhost:"+port+"/d","")));
    }
    @Test void draftIsIsolatedConflictIsRejectedAndRollbackRestoresWholeSnapshot() {
        var table=new RouteTable(null);var cat=catalog(9);
        var first=table.current();
        var saved=table.save(first.revision(),List.of(rule("custom-chat",100,D)),cat);
        assertThat(table.logical("custom-chat")).isFalse();
        assertThatThrownBy(()->table.save(first.revision(),List.of(),cat)).hasMessageContaining("已被更新");
        var published=table.publish(saved.revision(),rows->{});
        assertThat(table.logical("custom-chat")).isTrue();
        var second=table.save(published.revision(),List.of(rule("custom-chat",0,"none")),cat);
        table.publish(second.revision(),rows->{});
        var restored=table.rollback(table.current().revision(),rows->{});
        assertThat(restored.rules()).containsExactly(rule("custom-chat",100,D));
        assertThat(restored.version()).isEqualTo(4);
        assertThatThrownBy(()->table.save(restored.revision(),List.of(rule("custom-chat",101,"none")),cat)).hasMessageContaining("权重");
        assertThatThrownBy(()->table.save(restored.revision(),List.of(rule("dup",0,"none"),rule("dup",10,"none")),cat)).hasMessageContaining("只能有一条");
    }
    @Test void inheritedFallbackWorksForNonStreamingAndStreamingWithoutManifestFallbackAndReplayNeverCallsUpstream() throws Exception {
        var primary=new AtomicInteger();var alternate=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/q",e->{primary.incrementAndGet();e.getRequestBody().readAllBytes();var bytes="unavailable".getBytes();e.sendResponseHeaders(503,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/d",e->{alternate.incrementAndGet();var body=new String(e.getRequestBody().readAllBytes());var bytes=(body.contains("\"stream\":true")?
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}\n\ndata: [DONE]\n\n":
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}" ).getBytes();
            e.getResponseHeaders().add("Content-Type",body.contains("\"stream\":true")?"text/event-stream":"application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.start();
        try {
            var gateway=new Gateway(catalog(server.getAddress().getPort()),new BudgetCounter(),new Upstream());
            gateway.logicalRouting.save(1,List.of(rule("qa-route",100,D)));
            gateway.logicalRouting.publish(2);
            var created=gateway.create("route-check-staging",List.of("qa-route"),List.of(),new BigDecimal("100"),false);
            assertThat(gateway.requireAlias(created.alias()).fallback()).isEmpty();
            var response=gateway.complete("Bearer "+created.key(),"/v1/chat/completions","{\"model\":\"qa-route\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}",Duration.ofSeconds(3));
            assertThat(response.status()).isEqualTo(200);assertThat(primary).hasValue(1);assertThat(alternate).hasValue(1);
            var bytes=new java.io.ByteArrayOutputStream();
            gateway.stream("Bearer "+created.key(),"/v1/chat/completions","{\"model\":\"qa-route\",\"stream\":true}",Duration.ofSeconds(3),bytes,"trace-stream");
            assertThat(bytes.toString()).contains("[DONE]");assertThat(alternate).hasValue(2);
            int calls=primary.get()+alternate.get();
            var replay=gateway.logicalRouting.replay(3,gateway.spend(null,1,5000));
            assertThat(replay.get("requests")).isEqualTo(2);assertThat(replay.get("pricedRequests")).isEqualTo(2);
            assertThat(primary.get()+alternate.get()).isEqualTo(calls);
            assertThat(replay.toString()).doesNotContain("hello",created.key());
        }finally{server.stop(0);}
    }
    @Test void publishAndRollbackCheckReferencesAndCoverageAndGrantPreservesCredentialAndLimits() {
        var gateway=new Gateway(catalog(9),new BudgetCounter(),new Upstream());
        gateway.logicalRouting.save(1,List.of(rule("qa-route",100,"none")));
        gateway.logicalRouting.publish(2);
        var created=gateway.create("qa-policy-staging",List.of("qa-route"),List.of(),new BigDecimal("25"),false);
        gateway.updateBudget(created.alias(),new BigDecimal("25"),Map.of("rpm",12,"tpm",4000,"concurrency",2));
        gateway.logicalRouting.save(3,List.of(rule("qa-route",100,D)));
        assertThatThrownBy(()->gateway.logicalRouting.publish(4)).hasMessageContaining("补授权");
        gateway.addChannel("QA alternate",D,"staging",100);
        gateway.logicalRouting.grant(4);
        var key=gateway.requireAlias(created.alias());
        assertThat(key.token()).isEqualTo(created.key());assertThat(key.rpm()).isEqualTo(12);assertThat(key.tpm()).isEqualTo(4000);assertThat(key.dailyBudgetCny()).isEqualByComparingTo("25");
        assertThat(key.models()).contains(D);
        gateway.logicalRouting.publish(4);
        gateway.logicalRouting.save(5,List.of());
        assertThatThrownBy(()->gateway.logicalRouting.publish(6)).hasMessageContaining("仍被引用");
        assertThatThrownBy(()->gateway.logicalRouting.rollback(5)).hasMessageContaining("已被更新");
    }
    @Test void grantAddsSameEnvironmentChannelWithoutChangingExistingShares() {
        var gateway=new Gateway(catalog(9),new BudgetCounter(),new Upstream());
        gateway.logicalRouting.save(1,List.of(rule("qa-share",100,"none")));gateway.logicalRouting.publish(2);
        var created=gateway.create("qa-share-staging",List.of("qa-share"),List.of(),new BigDecimal("25"),false);
        gateway.updateChannels(created.alias(),List.of(new Gateway.Share("m-"+Q,4)),Map.of("tpm",700));
        gateway.addChannel("staging alternate",D,"staging",100);
        gateway.logicalRouting.save(3,List.of(rule("qa-share",100,D)));gateway.logicalRouting.grant(4);
        var key=gateway.requireAlias(created.alias());
        assertThat(key.shares()).hasSize(2).contains(new Gateway.Share("m-"+Q,4));
        assertThat(key.shares().getLast().rpm()).isEqualTo(1);
        assertThat(key.models()).contains("qa-share",Q,D);
        assertThat(key.tpm()).isEqualTo(700);
        assertThat(gateway.logicalRouting.view().get("gaps")).asList().isEmpty();
    }

    @Test void selectedChannelBreakerIsIsolatedFromOtherKeys() {
        var gateway=new Gateway(catalog(9),new BudgetCounter(),new Upstream());
        gateway.logicalRouting.save(1,List.of(rule("qa-isolated",100,D)));gateway.logicalRouting.publish(2);
        var created=gateway.create("qa-isolated-staging",List.of("qa-isolated"),List.of(),new BigDecimal("25"),false);
        var added=gateway.addChannel("QA isolated channel",Q,"staging",10);
        gateway.updateChannels(created.alias(),List.of(new Gateway.Share(added.id(),2),new Gateway.Share("m-"+D,2)),Map.of());
        gateway.simulateChannel(added.id());
        assertThat(gateway.preview(created.alias(),"qa-isolated").models()).containsExactly(D);
        var peer=gateway.create("qa-peer-staging",List.of(Q),List.of(),new BigDecimal("25"),false);
        assertThat(gateway.preview(peer.alias(),Q).models()).containsExactly(Q);
    }

    @Test void grayAndWeightedSelectionAreStableAndRespectBoundary() {
        var allGray=new RouteTable.Rule("qa","staging",Q,D,100,"none",100,D,0);
        var noGray=rule("qa",100,"none");var other=rule("qa",0,"none");
        for(int i=0;i<100;i++) {
            assertThat(allGray.select("request-"+i)).isEqualTo(D);
            assertThat(noGray.select("request-"+i)).isEqualTo(Q);
            assertThat(other.select("request-"+i)).isEqualTo(D);
        }
    }
}
