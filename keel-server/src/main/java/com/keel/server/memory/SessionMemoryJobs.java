package com.keel.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Transactional coalescing outbox, leased only to the owning agent's managed SDK. */
@Service
public class SessionMemoryJobs {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.keel.server.cost.TaskCostService taskCosts;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SessionMemoryJobs.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SessionSummaries summaries;
    private final SessionFacts facts;
    private final String enabled;
    @org.springframework.beans.factory.annotation.Autowired private MemoryMaintenancePolicy policy;
    public SessionMemoryJobs(JdbcTemplate jdbc,ObjectMapper json,SessionSummaries summaries,SessionFacts facts,
            @Value("${KEEL_MEMORY_ASYNC_AGENTS:}") String enabled) {
        this.jdbc=jdbc;this.json=json;this.summaries=summaries;this.facts=facts;this.enabled=enabled;
    }
    public boolean enabled(String agent,String env) {
        return Arrays.asList(enabled.split(",")).contains(agent+":"+env);
    }
    private void service(String agent,String env) {
        var p=ConsolePrincipal.current();
        if(p==null || !"SERVICE".equals(p.mode()) || !agent.equals(p.userId()) || !enabled(agent,env))
            throw denied();
    }
    private static KeelException denied() {return new KeelException(ErrorCode.MEMORY_WRITE_DENIED,"记忆任务身份、租约或范围无效");}
    private static String hash(String owner,String id) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((owner+":"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    /** Caller holds the session row lock inside appendTurn's transaction. */
    public void enqueue(String agent,String env,String owner,String rawSession,long userId,long summaryThrough,String trace) {
        if(!policy.enabledSession(agent,env,hash(owner,rawSession)))return;
        var costPolicy=taskCosts==null?null:taskCosts.memoryPolicy(agent,env);
        int batch=costPolicy==null?1:costPolicy.path("memoryBatchTurns").asInt(1);
        if(batch>1) {
            String text=jdbc.queryForObject("SELECT content FROM session_message WHERE id=? AND session_id=? AND owner_user=?",String.class,userId,rawSession,owner);
            if(text!=null&&text.strip().matches("(?i)(谢谢|多谢|感谢|好的|收到|嗯|ok|thanks|thank you)[。！!,.，\\s]*"))return;
        }
        put(agent,env,owner,rawSession,"facts",userId,trace);
        if(summaryThrough>0) {
            var valid=jdbc.queryForObject("SELECT count(*) FROM session_message WHERE id=? AND session_id=? AND agent_name=? AND owner_user=? AND role='assistant' AND expires_at>now()",Integer.class,summaryThrough,rawSession,agent,owner);
            if(valid!=null && valid==1)put(agent,env,owner,rawSession,"summary",summaryThrough,trace);
        }
        if(batch>1) {
            int wait=costPolicy.path("memoryMaxWaitSeconds").asInt(30);
            jdbc.update("""
                UPDATE session_memory_job j SET next_attempt_at=CASE
                WHEN (SELECT count(*) FROM session_message m WHERE m.session_id=j.session_id AND m.agent_name=j.agent_name AND m.owner_user=j.owner_user AND m.role='user' AND m.id>=COALESCE(j.batch_start_id,j.desired_id) AND m.id<=j.desired_id)>=?
                THEN now() ELSE LEAST(j.created_at+(?*interval '1 second'),now()+(?*interval '1 second')) END
                WHERE agent_name=? AND env=? AND owner_user=? AND session_id=? AND status='pending'
                """,batch,wait,wait,agent,env,owner,rawSession);
        }
    }
    private void put(String agent,String env,String owner,String session,String kind,long target,String trace) {
        jdbc.update("""
            INSERT INTO session_memory_job(agent_name,env,owner_user,session_id,kind,desired_id,trigger_trace_id,batch_start_id)
            VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(agent_name,env,owner_user,session_id,kind) DO UPDATE
            SET desired_id=GREATEST(session_memory_job.desired_id,EXCLUDED.desired_id),
                status=CASE WHEN session_memory_job.status='done' THEN 'pending' ELSE session_memory_job.status END,
                attempts=CASE WHEN session_memory_job.status='done' THEN 0 ELSE session_memory_job.attempts END,
                reserved_cny=CASE WHEN session_memory_job.status='done' THEN 0 ELSE session_memory_job.reserved_cny END,
                created_at=CASE WHEN session_memory_job.status='done' THEN now() ELSE session_memory_job.created_at END,
                batch_start_id=CASE WHEN session_memory_job.status='done' THEN EXCLUDED.batch_start_id ELSE session_memory_job.batch_start_id END,
                trigger_trace_id=EXCLUDED.trigger_trace_id,updated_at=now()
            """,agent,env,owner,session,kind,target,trace,target);
    }
    private String lockSession(Map<String,Object> job) {
        var rows=jdbc.query("SELECT id FROM agent_session WHERE id=? AND agent_name=? AND env=? AND owner_user=? FOR UPDATE",
                (rs,n)->rs.getString(1),job.get("session_id"),job.get("agent_name"),job.get("env"),job.get("owner_user"));
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional
    public Map<String,Object> claim(String agent,String env) { return claim(agent,env,""); }
    @Transactional
    public Map<String,Object> claim(String agent,String env,String worker) {
        return claimOne(agent,env,worker,null);
    }
    @Transactional
    public Map<String,Object> claim(String agent,String env,String worker,boolean combine) {
        var response=claimOne(agent,env,worker,null);
        if(!combine || response.get("job")==null)return response;
        @SuppressWarnings("unchecked") var first=(Map<String,Object>)response.get("job");
        var identity=jdbc.queryForMap("SELECT session_id,owner_user,kind FROM session_memory_job WHERE id=?",first.get("id"));
        var companion=claimOne(agent,env,worker,identity).get("job");
        var result=new LinkedHashMap<String,Object>(response);result.put("companion",companion);return result;
    }
    private Map<String,Object> claimOne(String agent,String env,String worker,Map<String,Object> pair) {
        service(agent,env);
        if(!worker.matches("[A-Za-z0-9_.:-]{0,128}"))throw denied();
        jdbc.update("UPDATE session_memory_attempt a SET status='abandoned',reason='lease_expired',finished_at=now() FROM session_memory_job j WHERE a.lease_token=j.lease_token AND a.status='running' AND j.agent_name=? AND j.env=? AND j.lease_until<now()",agent,env);
        // Exhausted abandoned attempts become visible terminal failures, not invisible pending work.
        int exhausted=jdbc.update("UPDATE session_memory_job SET status='failed',reason='attempts_exhausted',updated_at=now() WHERE agent_name=? AND env=? AND status IN ('pending','running') AND attempts>=3 AND (lease_until IS NULL OR lease_until<now())",agent,env);
        if(exhausted>0)log.error("trace_id=background agent={} memory_maintenance_failed reason=attempts_exhausted count={}",agent,exhausted);
        var args=new ArrayList<Object>(List.of(agent,env));
        String pairFilter="";
        if(pair!=null) {
            pairFilter=" AND j.session_id=? AND j.owner_user=? AND j.kind<>? ";
            args.add(pair.get("session_id"));args.add(pair.get("owner_user"));args.add(pair.get("kind"));
        }
        var jobs=jdbc.queryForList("""
            SELECT j.* FROM session_memory_job j JOIN agent_session s
            ON s.id=j.session_id AND s.agent_name=j.agent_name AND s.env=j.env AND s.owner_user=j.owner_user
            WHERE j.agent_name=? AND j.env=? AND COALESCE(s.memory_maintenance_mode,'async')='async' AND j.attempts<3 AND j.next_attempt_at<=now()
            AND (j.status='pending' OR (j.status='running' AND j.lease_until<now()))
            """+pairFilter+" ORDER BY j.updated_at,j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1",args.toArray());
        if(jobs.isEmpty()) return Collections.singletonMap("job",null);
        var job=jobs.getFirst();var scope=hash((String)job.get("owner_user"),(String)job.get("session_id"));
        String kind=(String)job.get("kind");long target=((Number)job.get("desired_id")).longValue();
        Map<String,Object> state;
        List<Map<String,Object>> messages;
        var more=false;List<?> skipped=List.of();String next;
        if(kind.equals("summary")) {
            state=summaries.get(agent,env,(String)job.get("owner_user"),scope);
            messages=jdbc.query("SELECT id,role,content FROM session_message WHERE session_id=? AND agent_name=? AND owner_user=? AND id>? AND id<=? AND expires_at>now() ORDER BY id LIMIT 130",
                (rs,n)->Map.<String,Object>of("id",rs.getString(1),"role",rs.getString(2),"content",rs.getString(3)),job.get("session_id"),agent,job.get("owner_user"),Long.parseLong(state.get("throughId").toString()),target);
            // Never give a dangling user at the transport page edge to a summarizer.
            if(!messages.isEmpty() && messages.getLast().get("role").equals("user")) messages=new ArrayList<>(messages.subList(0,messages.size()-1));
            next=messages.isEmpty()?state.get("throughId").toString():messages.getLast().get("id").toString();
            more=Long.parseLong(next)<target;
        } else {
            var batch=facts.get(agent,env,scope);
            state=json.convertValue(batch.get("state"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            messages=json.convertValue(batch.get("messages"),new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>(){});
            next=batch.get("nextCursor").toString();more=(Boolean)batch.get("more");skipped=(List<?>)batch.get("skippedIds");
            // Newly appended rows are allowed only on a fresh lease, never outside its snapshot.
            target=Math.max(target,Long.parseLong(next));
        }
        if(messages.isEmpty() && skipped.isEmpty()) {
            jdbc.update("UPDATE session_memory_job SET status=?,reason=?,updated_at=now() WHERE id=?",more?"failed":"done",more?"source_missing":null,job.get("id"));
            return Collections.singletonMap("job",null);
        }
        var lease=UUID.randomUUID().toString();long version=((Number)state.get("version")).longValue();
        jdbc.update("UPDATE session_memory_job SET status='running',lease_token=?,lease_until=now()+interval '75 seconds',target_id=?,expected_version=?,attempts=attempts+1,reserved_cny=reserved_cny+0.5,updated_at=now() WHERE id=?",
            lease,target,version,job.get("id"));
        jdbc.update("INSERT INTO session_memory_attempt(lease_token,job_id,agent_name,env,owner_user,session_id,kind,target_id,expected_version,attempt,worker_instance,trigger_trace_id,queued_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            lease,job.get("id"),agent,env,job.get("owner_user"),job.get("session_id"),kind,target,version,((Number)job.get("attempts")).intValue()+1,worker,job.get("trigger_trace_id"),job.get("created_at"));
        var result=new LinkedHashMap<String,Object>();
        result.put("queuedAt",((java.sql.Timestamp)job.get("created_at")).toInstant().toString());
        result.put("id",job.get("id"));result.put("kind",kind);result.put("env",env);result.put("ownerUser",job.get("owner_user"));
        result.put("sessionId",scope);result.put("targetId",Long.toString(target));result.put("expectedVersion",version);
        result.put("leaseToken",lease);result.put("triggerTraceId",Objects.toString(job.get("trigger_trace_id"),""));
        result.put("attempt",((Number)job.get("attempts")).intValue()+1);result.put("maxCalls",4);result.put("maxCostCny",0.5);
        result.put("maxInputTokens",10000);result.put("maxOutputTokens",1600);
        result.put("state",state);result.put("messages",messages);result.put("skippedIds",skipped);result.put("nextCursor",next);result.put("more",more);
        if(taskCosts!=null)result.put("costControl",true);
        return Map.of("job",result);
    }
    private Map<String,Object> leased(String agent,String env,JsonNode body) {
        service(agent,env);
        // Read identity before taking the session lock: all mutators use session -> job lock order.
        var rows=jdbc.queryForList("SELECT * FROM session_memory_job WHERE id=? AND agent_name=? AND env=?",body.path("id").asLong(-1),agent,env);
        if(rows.isEmpty() || lockSession(rows.getFirst())==null)throw denied();
        rows=jdbc.queryForList("SELECT * FROM session_memory_job WHERE id=? AND status='running' AND lease_token=? AND lease_until>now() FOR UPDATE",body.path("id").asLong(-1),body.path("leaseToken").asText());
        if(rows.isEmpty())throw denied();return rows.getFirst();
    }
    private void usage(JsonNode body) {
        if(body.path("calls").asInt(-1)<0 || body.path("calls").asInt()>4 || !body.path("costUpperBoundCny").isNumber()
                || body.path("costUpperBoundCny").decimalValue().signum()<0 || body.path("costUpperBoundCny").decimalValue().compareTo(new BigDecimal("0.5"))>0)
            throw denied();
    }
    @Transactional
    public Map<String,Object> costContext(String agent,String env,JsonNode body) {
        var job=leased(agent,env,body);
        var selected=costSources(agent,env,job,body.path("throughId").asLong(-1));
        if(body.path("companion").isObject()) {
            var other=leased(agent,env,body.path("companion"));
            if(!Objects.equals(job.get("session_id"),other.get("session_id"))||!Objects.equals(job.get("owner_user"),other.get("owner_user"))||Objects.equals(job.get("kind"),other.get("kind")))throw denied();
            var unique=new LinkedHashMap<String,Map<String,Object>>();
            java.util.stream.Stream.concat(selected.stream(),costSources(agent,env,other,body.path("companion").path("throughId").asLong(-1)).stream()).forEach(m->unique.put(m.get("id").toString(),m));
            selected=new ArrayList<>(unique.values());
        }
        var context=taskCosts.memory(agent,env,job.get("owner_user").toString(),selected);
        return context==null?Map.of():json.convertValue(context,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    private List<Map<String,Object>> costSources(String agent,String env,Map<String,Object> job,long through) {
        if(through<1||through>((Number)job.get("target_id")).longValue())throw denied();
        String scope=hash(job.get("owner_user").toString(),job.get("session_id").toString());
        String table="summary".equals(job.get("kind"))?"session_summary":"session_fact_state";
        String cursor="summary".equals(job.get("kind"))?"through_id":"cursor_id";
        var before=jdbc.query("SELECT "+cursor+" FROM "+table+" WHERE agent_name=? AND env=? AND owner_user=? AND session_id=?",(rs,n)->rs.getLong(1),agent,env,job.get("owner_user"),scope);
        long first=before.isEmpty()?0:before.getFirst();
        if(through<=first)throw denied();
        return jdbc.query("SELECT id,role FROM session_message WHERE agent_name=? AND owner_user=? AND session_id=? AND id>? AND id<=? AND expires_at>now() ORDER BY id",
                (rs,n)->Map.<String,Object>of("id",rs.getString(1),"role",rs.getString(2)),agent,job.get("owner_user"),job.get("session_id"),first,through);
    }
    @Transactional
    public Map<String,Object> complete(String agent,String env,JsonNode body) {
        var job=leased(agent,env,body);usage(body);
        var state=body.path("state");String kind=(String)job.get("kind");String hash=hash((String)job.get("owner_user"),(String)job.get("session_id"));
        long through=state.path(kind.equals("summary")?"throughId":"cursorId").asLong(-1);
        if(state.path("version").asLong(-1)!=((Number)job.get("expected_version")).longValue() || through<=0 || through>((Number)job.get("target_id")).longValue())throw denied();
        var valid=jdbc.queryForObject("SELECT count(*) FROM session_message WHERE id=? AND session_id=? AND agent_name=? AND owner_user=? AND role=? AND expires_at>now()",Integer.class,through,job.get("session_id"),agent,job.get("owner_user"),kind.equals("summary")?"assistant":"user");
        if(valid==null || valid!=1)throw denied();
        var payload=json.createObjectNode().put("env",env).put("sessionId",hash).put("ownerUser",(String)job.get("owner_user"));
        Map<String,Object> saved;
        if(kind.equals("summary")) {state.fields().forEachRemaining(e->payload.set(e.getKey(),e.getValue()));saved=summaries.save(agent,payload);}
        else {payload.put("maintenanceThroughId",((Number)job.get("target_id")).longValue());payload.set("state",state);saved=facts.save(agent,payload);}
        boolean ok=Boolean.TRUE.equals(saved.get("saved"));
        var desired=jdbc.queryForObject("SELECT desired_id FROM session_memory_job WHERE id=?",Long.class,job.get("id"));
        boolean more=!ok || desired>through;
        jdbc.update("UPDATE session_memory_job SET status=?,lease_token=NULL,lease_until=NULL,attempts=?,reserved_cny=?,calls=calls+?,cost_upper_bound=cost_upper_bound+?,worker_trace_id=?,reason=?,next_attempt_at=now(),updated_at=now() WHERE id=?",
            more?"pending":"done",ok?0:job.get("attempts"),ok?BigDecimal.ZERO:job.get("reserved_cny"),body.path("calls").asInt(),body.path("costUpperBoundCny").decimalValue(),body.path("traceId").asText(""),ok?null:"conflict",job.get("id"));
        jdbc.update("UPDATE session_memory_attempt SET status=?,reason=?,finished_at=now(),through_id=?,more=?,calls=?,input_tokens=?,output_tokens=?,cost_upper_bound=?,worker_trace_id=? WHERE lease_token=?",
            ok?"committed":"conflict",ok?null:"conflict",through,more,body.path("calls").asInt(),body.path("inputTokens").isIntegralNumber()?body.path("inputTokens").asInt():null,body.path("outputTokens").isIntegralNumber()?body.path("outputTokens").asInt():null,body.path("costUpperBoundCny").decimalValue(),body.path("traceId").asText(""),body.path("leaseToken").asText());
        return Map.of("saved",ok,"more",more);
    }
    @Transactional
    public Map<String,Object> fail(String agent,String env,JsonNode body) {
        var job=leased(agent,env,body);usage(body);var reason=body.path("reason").asText();
        if(!Set.of("timeout","upstream","invalid_output","source_too_large","budget","cancelled").contains(reason))throw denied();
        boolean terminal=((Number)job.get("attempts")).intValue()>=3 || Set.of("source_too_large","budget").contains(reason);
        jdbc.update("UPDATE session_memory_job SET status=?,reason=?,lease_token=NULL,lease_until=NULL,next_attempt_at=now()+interval '2 seconds',calls=calls+?,cost_upper_bound=cost_upper_bound+?,worker_trace_id=?,updated_at=now() WHERE id=?",
            terminal?"failed":"pending",reason,body.path("calls").asInt(),body.path("costUpperBoundCny").decimalValue(),body.path("traceId").asText(""),job.get("id"));
        jdbc.update("UPDATE session_memory_attempt SET status='failed',reason=?,finished_at=now(),calls=?,input_tokens=?,output_tokens=?,cost_upper_bound=?,worker_trace_id=? WHERE lease_token=?",
            reason,body.path("calls").asInt(),body.path("inputTokens").isIntegralNumber()?body.path("inputTokens").asInt():null,body.path("outputTokens").isIntegralNumber()?body.path("outputTokens").asInt():null,body.path("costUpperBoundCny").decimalValue(),body.path("traceId").asText(""),body.path("leaseToken").asText());
        if(terminal)log.error("trace_id={} agent={} memory_maintenance_failed task={} reason={}",body.path("traceId").asText("background"),agent,job.get("id"),reason);
        return Map.of("accepted",true);
    }
    public Map<String,Object> status(String agent,String env) {
        var p=ConsolePrincipal.current();if(p==null || p.mode().equals("PREVIEW") || (p.mode().equals("SERVICE") && !agent.equals(p.userId())))throw denied();
        String filter=p.mode().equals("SERVICE")?"":" AND owner_user=?";
        var args=new ArrayList<Object>(List.of(agent,env));if(!filter.isEmpty())args.add(p.userId());
        var rows=jdbc.queryForList("SELECT status,count(*) AS count FROM session_memory_job WHERE agent_name=? AND env=?"+filter+" GROUP BY status",args.toArray());
        var age=jdbc.queryForObject("SELECT coalesce(max(extract(epoch FROM (now()-created_at))),0) FROM session_memory_job WHERE agent_name=? AND env=? AND status IN ('pending','running')"+filter,Double.class,args.toArray());
        return Map.of("enabled",enabled(agent,env),"counts",rows,"oldestPendingSeconds",age);
    }
}
