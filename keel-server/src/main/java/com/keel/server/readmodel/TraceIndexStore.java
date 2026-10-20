package com.keel.server.readmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.server.insight.TraceAssembly;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Only writes disposable read-model tables. Business invocation/audit/run tables remain read-only. */
@Component
public class TraceIndexStore {
    public static final String KEY = "trace-index-v1";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InsightSnapshots snapshots;
    private final TransactionTemplate transaction;
    public TraceIndexStore(JdbcTemplate jdbc, ObjectMapper json, InsightSnapshots snapshots, PlatformTransactionManager tx) {
        this.jdbc=jdbc; this.json=json; this.snapshots=snapshots; this.transaction=new TransactionTemplate(tx);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public void accept(String owner, List<JsonNode> rows, Object checkpoint, Duration delay) {
        transaction.executeWithoutResult(tx -> {
            if (!snapshots.lockOwner(KEY, owner)) return;
            var changed = new LinkedHashSet<String>();
            for (var row : rows) {
                var id=row.path("id").asText(""); var trace=row.path("traceId").asText("");
                var start=row.path("startTime").asText("");
                if (id.isBlank() || trace.isBlank() || start.isBlank()) continue;
                var clean = sanitize(row);
                jdbc.update("""
                        INSERT INTO insight_observation(observation_id,trace_id,started_at,payload) VALUES (?,?,?,?::jsonb)
                        ON CONFLICT(observation_id) DO UPDATE SET payload=insight_observation.payload || excluded.payload
                        """,id,trace,Timestamp.from(Instant.parse(start)),snapshots.encode(clean));
                changed.add(trace);
            }
            for (var trace : changed) {
                var nodes=jdbc.query("SELECT payload::text FROM insight_observation WHERE trace_id=? ORDER BY started_at,observation_id",
                        (rs,n)-> snapshots.decode(rs.getString(1), JsonNode.class),trace);
                var summary=TraceAssembly.listSummary(trace,nodes);
                if (summary == null || !summary.containsKey("startedAt")) continue;
                jdbc.update("""
                        INSERT INTO insight_trace(trace_id,started_at,payload) VALUES (?,?,?::jsonb)
                        ON CONFLICT(trace_id) DO UPDATE SET started_at=excluded.started_at,payload=excluded.payload
                        """,trace,Timestamp.from(Instant.parse(summary.get("startedAt").toString())),snapshots.encode(summary));
            }
            if (!snapshots.finish(KEY,owner,checkpoint,delay)) tx.setRollbackOnly();
        });
    }
    /** Root titles are capped; no generated text, replies or system prompt is persisted by the indexer. */
    static ObjectNode sanitize(JsonNode row) {
        var copy=(ObjectNode)row.deepCopy();
        boolean root=row.path("parentObservationId").asText("").isBlank();
        var question=root ? TraceAssembly.listSummary(row.path("traceId").asText(),List.of(row)) : null;
        copy.remove(List.of("input","output"));
        if (copy.path("metadata") instanceof ObjectNode meta) {
            meta.remove(List.of("langfuse.observation.input","langfuse.observation.output",
                    "attributes.langfuse.observation.input","attributes.langfuse.observation.output"));
            if (meta.path("attributes") instanceof ObjectNode attrs) attrs.remove(List.of("langfuse.observation.input","langfuse.observation.output"));
        }
        if (question != null && question.get("question") instanceof String text && !text.isBlank())
            copy.put("input",text.substring(0,Math.min(512,text.length())));
        return copy;
    }

    // Merge local probes and current suspended runs before filtering/counting/pagination, with one row per trace.
    private static final String BASE = """
        WITH base AS (
          SELECT trace_id,started_at,payload FROM insight_trace
          UNION ALL
          SELECT i.trace_id,i.started_at,jsonb_build_object('traceId',i.trace_id,'rootAgent',i.agent,'agents',jsonb_build_array(i.agent),
            'env',i.env,'question',i.question,'startedAt',i.started_at,'durationMs',i.duration_ms,'status',i.status)
          FROM invoke_trace i WHERE NOT EXISTS(SELECT 1 FROM insight_trace t WHERE t.trace_id=i.trace_id)
          UNION ALL
          (SELECT DISTINCT ON (r.trace_id) r.trace_id,r.created_at,jsonb_build_object('traceId',r.trace_id,'rootAgent',r.agent_name,
            'agents',jsonb_build_array(r.agent_name),'env',r.env,'question',r.prompt,'startedAt',r.created_at,'status','ok')
          FROM agent_run r WHERE r.status='SUSPENDED' AND r.trace_id IS NOT NULL
            AND NOT EXISTS(SELECT 1 FROM insight_trace t WHERE t.trace_id=r.trace_id)
            AND NOT EXISTS(SELECT 1 FROM invoke_trace i WHERE i.trace_id=r.trace_id)
          ORDER BY r.trace_id,r.created_at DESC)
        ), live AS (
          SELECT b.trace_id,b.started_at,b.payload || COALESCE(bill.payload,'{}'::jsonb) ||
            CASE WHEN r.status='SUSPENDED' THEN jsonb_build_object('pendingReason',r.suspend_reason,'runId',r.run_id,
              'userId',COALESCE(b.payload->>'userId',r.actor_user),'humanWaitMs',GREATEST(0,(extract(epoch from (now()-r.created_at))*1000)::bigint))
              ELSE '{}'::jsonb END AS payload
          FROM base b
          LEFT JOIN LATERAL (SELECT status,suspend_reason,run_id,actor_user,created_at FROM agent_run WHERE trace_id=b.trace_id ORDER BY created_at DESC LIMIT 1) r ON true
          LEFT JOIN insight_trace_bill bill ON bill.trace_id=b.trace_id
        )
        """;
    public Map<String,Object> list(int page,int size,String agent,String env,String status,Instant from,Instant to,boolean multi,Integer minMs,String job) {
        var where=new StringBuilder(" WHERE 1=1"); var args=new ArrayList<Object>();
        if (agent != null && !agent.isBlank()) { where.append(" AND (payload->>'rootAgent'=? OR jsonb_exists(payload->'agents',?))");args.add(agent);args.add(agent); }
        if (env != null && !env.isBlank() && !"all".equals(env)) {where.append(" AND payload->>'env'=?");args.add(env);}
        if (job != null && !job.isBlank()) {where.append(" AND payload->>'devflowJobId'=?");args.add(job);}
        if (from != null) {where.append(" AND started_at>=?");args.add(Timestamp.from(from));}
        if (to != null) {where.append(" AND started_at<?");args.add(Timestamp.from(to));}
        if (multi) where.append(" AND payload->>'multiAgent'='true'");
        if (minMs != null && minMs>0) {where.append(" AND (payload->>'durationMs')::bigint>=?");args.add(minMs);}
        if (status != null && !status.isBlank()) {
            if ("pending".equals(status)) where.append(" AND COALESCE(payload->>'pendingReason','')<>''");
            else if ("blocked".equals(status)) where.append(" AND payload->>'blocked'='true'");
            else {where.append(" AND COALESCE(payload->>'pendingReason','')='' AND COALESCE(payload->>'blocked','false')<>'true' AND payload->>'status'=?"); args.add(status);}
        }
        // A repeatable-read transaction makes count and rows describe the same snapshot during concurrent ingestion.
        return transaction.execute(tx -> {
            var total=jdbc.queryForObject(BASE+" SELECT count(*) FROM live"+where,Long.class,args.toArray());
            var paged=new ArrayList<>(args);paged.add(size);paged.add((long)(page-1)*size);
            var items=jdbc.query(BASE+" SELECT payload::text FROM live"+where+" ORDER BY started_at DESC,trace_id LIMIT ? OFFSET ?",
                    (rs,n)->map(rs.getString(1)),paged.toArray());
            for(var item:items) if(item.get("pendingReason") instanceof String reason) item.put("pendingReason",TraceAssembly.pendingText(reason));
            return new LinkedHashMap<>(Map.of("page",page,"size",size,"total",total,"items",items));
        });
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(String text) {return snapshots.decode(text,LinkedHashMap.class);}
    @org.springframework.scheduling.annotation.Scheduled(cron="0 25 3 * * *",zone="Asia/Shanghai")
    public void prune() {
        jdbc.update("DELETE FROM insight_observation WHERE started_at < now()-interval '30 days'");
        jdbc.update("DELETE FROM insight_trace WHERE started_at < now()-interval '30 days'");
    }
    public List<Map<String,Object>> usage(Instant from,Instant to,String env) {
        return jdbc.query("SELECT payload::text FROM insight_trace WHERE COALESCE(payload->>'_evaluation','false')<>'true' AND started_at>=? AND started_at<? AND (?='all' OR payload->>'env'=?)",
                (rs,n)->map(rs.getString(1)),Timestamp.from(from),Timestamp.from(to),env,env);
    }
    public InsightSnapshots.Snapshot billingSnapshot() { return snapshots.read("overview-ledger-v1"); }
    public boolean replaceBills(String owner, com.keel.server.insight.CostService.Ledger ledger) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            if(!snapshots.lockOwner("overview-ledger-v1",owner)) return false;
            var bills=ledger.bills();
            var totals=new LinkedHashMap<String,Map<String,Object>>();
            for(var bill:bills) {
                if(bill.traceId()==null || bill.traceId().isBlank()) continue;
                var value=totals.computeIfAbsent(bill.traceId(),k->new LinkedHashMap<>());
                if("SETTLED".equals(bill.status()) && bill.costCny()!=null) value.merge("costCny",bill.costCny(),(a,b)->((Number)a).doubleValue()+((Number)b).doubleValue());
                if("PENDING".equals(bill.status())) {
                    value.putIfAbsent("costCny",null);
                    value.merge("pendingBillCount",1,(a,b)->((Number)a).intValue()+1);
                }
            }
            jdbc.update("DELETE FROM insight_trace_bill");
            totals.forEach((id,value)->jdbc.update("INSERT INTO insight_trace_bill(trace_id,payload) VALUES (?,?::jsonb)",id,snapshots.encode(value)));
            boolean completed=snapshots.finish("overview-ledger-v1",owner,ledger,Duration.ofSeconds(60));
            if(!completed) tx.setRollbackOnly();
            return completed;
        }));
    }
}
