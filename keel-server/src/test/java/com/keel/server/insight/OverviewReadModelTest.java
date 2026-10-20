package com.keel.server.insight;

import com.keel.server.readmodel.InsightSnapshots;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class OverviewReadModelTest {
    @Test void coldAndConcurrentReadsNeverWaitForExternalStatisticsAndRestartReusesTheSharedResult() throws Exception {
        var store=new MemorySnapshots();
        var started=new CountDownLatch(1); var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var externalCalls=new java.util.concurrent.atomic.AtomicInteger();
        var service=new OverviewService(null,null,null,null,null,null,null) {
            @Override public Overview local(String env) {return value(null);}
            @Override public Overview overview(String env,String range) {
                externalCalls.incrementAndGet();started.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return value(42L);
            }
        };
        var sync=new TraceIndexSync(null,null,null) {
            @Override public Map<String,Object> status(Instant from,Instant to) {return Map.of("state","ready","complete",true);}
        };
        var first=new OverviewReadModel(store,service,sync);var second=new OverviewReadModel(store,service,sync);
        try {
            var cold=first.read("prod","24h");
            assertThat(cold.kpi().calls()).isNull();assertThat(cold.kpi().onlineAgents()).isEqualTo(2);
            assertThat(cold.dataStatus().get("state")).isEqualTo("loading");assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++) assertThat(second.read("prod","24h").kpi().calls()).isNull();
            assertThat(externalCalls.get()).isEqualTo(1);
            release.countDown();
            for(int i=0;i<100 && store.read("overview-v1:prod:24h").payload()==null;i++) Thread.sleep(10);
            assertThat(second.read("prod","24h").kpi().calls()).isEqualTo(42L);
            assertThat(second.read("dev","24h").kpi().calls()).isNull();
        } finally {release.countDown();first.close();second.close();}
    }
    @Test void failedBackgroundRefreshRetainsKnownValuesAndNeverReportsSuccessfulFreshness() throws Exception {
        var store=new MemorySnapshots();
        store.snapshots.put("overview-v1:all:24h",new InsightSnapshots.Snapshot(store.encode(value(42L)),Instant.now().minusSeconds(200),true));
        var service=new OverviewService(null,null,null,null,null,null,null) {
            @Override public Overview overview(String env,String range) {throw new IllegalStateException("upstream down");}
        };
        var sync=new TraceIndexSync(null,null,null) {@Override public Map<String,Object> status(Instant from,Instant to) {return Map.of("complete",true);}};
        var model=new OverviewReadModel(store,service,sync);
        try {
            var result=model.read("all","24h");assertThat(result.kpi().calls()).isEqualTo(42L);
            assertThat(result.dataStatus().get("state")).isEqualTo("stale");
        } finally {model.close();}
    }
    static OverviewService.Overview value(Long calls) {
        return new OverviewService.Overview(new OverviewService.Kpi(2,3,1,1,calls,null,null,null,.85,0,null),List.of(),List.of(),List.of(),null,List.of(),LayerValue.empty());
    }
    static class MemorySnapshots extends InsightSnapshots {
        final Map<String,Snapshot> snapshots=new ConcurrentHashMap<>();final Set<String> leases=ConcurrentHashMap.newKeySet();
        MemorySnapshots() {super(null,new ObjectMapper().findAndRegisterModules());}
        @Override public Snapshot read(String key) {return snapshots.getOrDefault(key,new Snapshot(null,null,false));}
        @Override public String claim(String key,Duration lease) {return leases.add(key)?"owner":null;}
        @Override public boolean finish(String key,String owner,Object value,Duration delay) {snapshots.put(key,new Snapshot(encode(value),Instant.now(),false));return true;}
        @Override public void fail(String key,String owner,Duration delay) {var old=read(key);snapshots.put(key,new Snapshot(old.payload(),old.at(),true));}
    }
}
