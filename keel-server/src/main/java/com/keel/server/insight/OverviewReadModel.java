package com.keel.server.insight;

import com.keel.server.readmodel.InsightSnapshots;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;

@Component
public class OverviewReadModel {
    private final InsightSnapshots snapshots;
    private final OverviewService overview;
    private final TraceIndexSync traceSync;
    private final Set<String> requested=ConcurrentHashMap.newKeySet();
    private final Set<String> running=ConcurrentHashMap.newKeySet();
    private final ExecutorService refresh=Executors.newFixedThreadPool(2,Thread.ofPlatform().daemon().name("overview-refresh-",0).factory());
    public OverviewReadModel(InsightSnapshots snapshots,OverviewService overview,TraceIndexSync traceSync) {
        this.snapshots=snapshots;this.overview=overview;this.traceSync=traceSync;requested.add("all:24h");
    }
    public OverviewService.Overview read(String env,String range) {
        var key=env+":"+range; requested.add(key);
        var snap=snapshots.read("overview-v1:"+key);
        if(snap.at()==null || snap.at().plusSeconds(60).isBefore(Instant.now())) schedule(key);
        var result=snap.payload()==null?overview.local(env):snapshots.decode(snap.payload(),OverviewService.Overview.class);
        var status=InsightSnapshots.status(snap,snap.at()!=null,null,null);
        if(snap.at()!=null && snap.at().plusSeconds(120).isBefore(Instant.now())) status.put("state","stale");
        // Trace coverage is independently visible: a freshly aggregated partial index is not a complete statistic.
        var window="30d".equals(range)?Duration.ofDays(30):"7d".equals(range)?Duration.ofDays(7):Duration.ofHours(24);
        var trace=result.dataStatus()==null ? traceSync.status(Instant.now().minus(window),null) : result.dataStatus();
        if(!Boolean.TRUE.equals(trace.get("complete"))) {status.put("complete",false);if(!snap.failed()) status.put("state","loading");}
        return result.withStatus(status);
    }
    @Scheduled(initialDelay=5000,fixedDelay=30000) public void tick() {for(var key:requested) schedule(key);}
    private void schedule(String key) {
        if(!running.add(key)) return;
        refresh.execute(()->{
            String owner=null;
            try {
                owner=snapshots.claim("overview-v1:"+key,Duration.ofSeconds(180));
                if(owner==null) return;
                var split=key.split(":");
                var window="30d".equals(split[1])?Duration.ofDays(30):"7d".equals(split[1])?Duration.ofDays(7):Duration.ofHours(24);
                var value=overview.overview(split[0],split[1]).withStatus(traceSync.status(Instant.now().minus(window),null));
                snapshots.finish("overview-v1:"+key,owner,value,Duration.ofSeconds(60));
            } catch(RuntimeException error) {
                if(owner!=null) snapshots.fail("overview-v1:"+key,owner,Duration.ofSeconds(30));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("trace_id=background agent=keel overview refresh failed: {}",error.getClass().getSimpleName());
            } finally {running.remove(key);}
        });
    }
    @PreDestroy public void close() {refresh.shutdownNow();}
}
