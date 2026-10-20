package com.keel.server.insight;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.langfuse.LangfuseRateLimit;
import com.keel.server.readmodel.InsightSnapshots;
import com.keel.server.readmodel.TraceIndexStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class TraceIndexSync {
    private static final Logger log=LoggerFactory.getLogger(TraceIndexSync.class);
    private final InsightSnapshots snapshots;
    private final TraceIndexStore index;
    private final LangfuseClient langfuse;
    public record Checkpoint(Instant from, Instant to, boolean roots, String cursor,
                             Instant coveredFrom, Instant completedTo, Instant fullAt) {}
    public TraceIndexSync(InsightSnapshots snapshots, TraceIndexStore index, LangfuseClient langfuse) {
        this.snapshots=snapshots; this.index=index; this.langfuse=langfuse;
    }
    @Scheduled(initialDelay=3000, fixedDelay=8000)
    public void tick() {
        var owner=snapshots.claim(TraceIndexStore.KEY,Duration.ofSeconds(90));
        if(owner==null) return;
        try {
            var old=snapshots.read(TraceIndexStore.KEY);
            var now=Instant.now();
            var state=old.payload()==null ? new Checkpoint(now.minus(Duration.ofDays(30)),now,true,null,null,null,null)
                    : snapshots.decode(old.payload(),Checkpoint.class);
            var body=langfuse.observationIndexPage(state.from(),state.to(),state.roots(),state.cursor());
            if(!body.path("data").isArray()) throw new IllegalStateException("Observation page has no data array");
            var cursor=body.path("meta").path("cursor").asText("");
            if(!cursor.isBlank() && cursor.equals(state.cursor())) throw new IllegalStateException("Observation cursor did not advance");
            var next=advance(state,cursor,now);
            var rows=new ArrayList<JsonNode>();body.path("data").forEach(rows::add);
            index.accept(owner,rows,next, !state.roots() && cursor.isBlank() ? Duration.ofSeconds(30) : Duration.ofSeconds(8));
        } catch (LangfuseRateLimit error) {
            snapshots.fail(TraceIndexStore.KEY,owner,Duration.ofSeconds(Math.max(8,error.retryAfterSeconds())));
            log.warn("trace_id=background agent=keel trace index limited; cursor retained");
        } catch (RuntimeException error) {
            snapshots.fail(TraceIndexStore.KEY,owner,Duration.ofSeconds(60));
            log.warn("trace_id=background agent=keel trace index refresh failed: {}",error.getClass().getSimpleName());
        }
    }
    static Checkpoint advance(Checkpoint state,String cursor,Instant now) {
        if(!cursor.isBlank()) return new Checkpoint(state.from(),state.to(),state.roots(),cursor,state.coveredFrom(),state.completedTo(),state.fullAt());
        if(state.roots()) return new Checkpoint(state.from(),state.to(),false,null,state.coveredFrom(),state.completedTo(),state.fullAt());
        boolean full=state.coveredFrom()==null || state.from().isBefore(state.to().minus(Duration.ofHours(2)));
        var fullAt=full ? now : state.fullAt();
        boolean reconcile=fullAt==null || fullAt.plus(Duration.ofMinutes(30)).isBefore(now);
        return new Checkpoint(reconcile ? now.minus(Duration.ofDays(7)) : state.to().minus(Duration.ofHours(1)),now,
                true,null, full && (state.coveredFrom()==null || state.from().isBefore(state.coveredFrom())) ? state.from() : state.coveredFrom(),state.to(),fullAt);
    }
    public Map<String,Object> status(Instant from,Instant to) {
        var snap=snapshots.read(TraceIndexStore.KEY);
        var point=snap.payload()==null?null:snapshots.decode(snap.payload(),Checkpoint.class);
        boolean complete=point!=null && point.coveredFrom()!=null && (from==null || !from.isBefore(point.coveredFrom().plusSeconds(60)));
        var status=InsightSnapshots.status(snap,complete,point==null?null:point.coveredFrom(),point==null?null:point.completedTo());
        if(complete && point.completedTo()!=null && point.completedTo().plusSeconds(180).isBefore(Instant.now())) status.put("state","stale");
        return status;
    }
}
