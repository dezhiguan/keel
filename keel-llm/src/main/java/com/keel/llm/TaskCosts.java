package com.keel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Task admission is serialized in Postgres; actual money is always read from llm_spend. */
public final class TaskCosts {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public TaskCosts(javax.sql.DataSource source) {
        db = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    public Map<String,Object> open(JsonNode body) {
        String alias=body.path("alias").asText(),owner=body.path("owner").asText(),group=body.path("group").asText();
        JsonNode policy=body.path("policy");
        String mode=policy.path("mode").asText();
        BigDecimal cap=body.path("budgetCny").decimalValue();
        int calls=policy.path("maxCalls").asInt();
        if(!Gateway.ALIAS.matcher(alias).matches() || owner.isBlank() || owner.length()>200
                || !Set.of("user","eval","load","service","memory").contains(group)
                || !Set.of("observe","enforce").contains(mode) || cap.signum()<=0
                || cap.compareTo(policy.path("maxCostCny").decimalValue())>0 || calls<1 || calls>1000)
            throw denied();
        return tx.execute(transaction->{
        String id="ct_"+UUID.randomUUID().toString().replace("-","");
        String ticket="tc_"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        db.update("INSERT INTO llm_cost_task(id,alias,owner_user,traffic_group,ticket_hash,policy,budget_cny,max_calls,mode) VALUES(?,?,?,?,?,?::jsonb,?,?,?)",
                id,alias,owner,group,hash(ticket),policy.toString(),cap,calls,mode);
        if(body.path("shares").isArray()&&!body.path("shares").isEmpty()) {
            if(!group.equals("memory")||body.path("shares").size()>128)throw denied();
            BigDecimal total=BigDecimal.ZERO;var targets=new HashSet<String>();
            for(var share:body.path("shares")) {
                String target=share.path("taskId").asText();BigDecimal weight=share.path("weight").decimalValue();
                if(!targets.add(target)||weight.signum()<=0||weight.scale()>15||weight.compareTo(BigDecimal.ONE)>0||db.queryForObject("SELECT count(*) FROM llm_cost_task WHERE id=? AND alias=? AND owner_user=?",Integer.class,target,alias,owner)!=1)throw denied();
                db.update("INSERT INTO llm_cost_task_share(source_task_id,target_task_id,weight) VALUES(?,?,?)",id,target,weight);
                total=total.add(weight);
            }
            if(total.compareTo(BigDecimal.ONE)!=0)throw denied();
        }
        if(mode.equals("enforce"))required(alias,true);
        return Map.of("taskId",id,"ticket",ticket,"policyVersion",policy.path("version").asText(),"mode",mode,"features",policy);
        });
    }

    public void required(String alias,boolean required) {
        if(!Gateway.ALIAS.matcher(alias).matches())throw denied();
        db.update("INSERT INTO llm_cost_required_alias(alias,required) VALUES(?,?) ON CONFLICT(alias) DO UPDATE SET required=EXCLUDED.required",alias,required);
    }
    public void checkContext(String alias,String ticket) {
        if(ticket==null||ticket.isBlank()) {
            if(Boolean.TRUE.equals(db.query("SELECT required FROM llm_cost_required_alias WHERE alias=?",rs->rs.next()?rs.getBoolean(1):false,alias)))throw denied();
        }
    }

    public void admit(String alias,String ticket,String requestId,String purpose,BigDecimal reserve,int attempts) {
        if(ticket==null || ticket.isBlank())return;
        if(!ticket.matches("tc_[a-f0-9]{64}") || !Set.of("interactive","classify","analysis","memory").contains(purpose))throw denied();
        boolean accepted=Boolean.TRUE.equals(tx.execute(status->{
            var rows=db.queryForList("SELECT * FROM llm_cost_task WHERE ticket_hash=? AND alias=?",hash(ticket),alias);
            if(rows.isEmpty())throw denied();
            var task=rows.getFirst();String id=(String)task.get("id");
            var locked=db.queryForList("SELECT * FROM llm_cost_task WHERE id=? OR id IN(SELECT target_task_id FROM llm_cost_task_share WHERE source_task_id=?) ORDER BY id FOR UPDATE",id,id);
            if(db.queryForObject("SELECT count(*) FROM llm_cost_request WHERE request_id=?",Integer.class,requestId)>0)throw denied();
            // Reconcile only definitive terminal rows. Unknown transport attempts retain their reservation.
            reconcile(id);
            boolean violation=false;
            for(var target:locked) {
                String targetId=target.get("id").toString();reconcile(targetId);
                var totals=totals(targetId);
                BigDecimal weight=targetId.equals(id)?BigDecimal.ONE:db.queryForObject("SELECT weight FROM llm_cost_task_share WHERE source_task_id=? AND target_task_id=?",BigDecimal.class,id,targetId);
                BigDecimal exposure=money(totals.get("settledCny")).add(money(totals.get("reservedCny"))).add(reserve.multiply(weight));
                int used=((Number)totals.get("attempts")).intValue()+((Number)totals.get("reservedCalls")).intValue()+attempts;
                boolean over=exposure.compareTo(money(target.get("budget_cny")))>0 || used>((Number)target.get("max_calls")).intValue();
                if(over&&"enforce".equals(target.get("mode")))return false;
                violation|=over;
            }
            db.update("INSERT INTO llm_cost_request(request_id,task_id,purpose,reserved_cny,reserved_calls,violation) VALUES(?,?,?,?,?,?)",
                    requestId,id,purpose,reserve,attempts,violation);
            return true;
        }));
        if(!accepted)throw new Gateway.GatewayException(ErrorCode.COST_BUDGET_EXCEEDED,ErrorCode.COST_BUDGET_EXCEEDED.message());
    }

    public void reconcileRequest(String requestId) {
        var ids=db.query("SELECT task_id FROM llm_cost_request WHERE request_id=?",(rs,n)->rs.getString(1),requestId);
        if(ids.isEmpty())return;
        tx.executeWithoutResult(s->{db.queryForList("SELECT id FROM llm_cost_task WHERE id=? FOR UPDATE",ids.getFirst());reconcile(ids.getFirst());});
    }

    private void reconcile(String id) {
        db.update("""
            UPDATE llm_cost_request r SET state='settled',reserved_cny=0,reserved_calls=0
            WHERE (task_id=? OR task_id IN(SELECT source_task_id FROM llm_cost_task_share WHERE target_task_id=?))
            AND EXISTS(SELECT 1 FROM llm_spend s WHERE s.request_id=r.request_id)
            AND NOT EXISTS(SELECT 1 FROM llm_spend s WHERE s.request_id=r.request_id
                AND (s.status='PENDING' OR s.cost_cny IS NULL OR s.timed_out))
            """,id,id);
    }

    private Map<String,Object> totals(String id) {
        String refs="WITH refs AS (SELECT ?::varchar AS id,1::numeric AS weight UNION ALL SELECT source_task_id,weight FROM llm_cost_task_share WHERE target_task_id=?) ";
        BigDecimal settled=db.queryForObject(refs+"SELECT COALESCE(sum(s.cost_cny*f.weight),0) FROM refs f JOIN llm_cost_request r ON r.task_id=f.id JOIN llm_spend s USING(request_id) WHERE s.status='SETTLED' AND s.cost_cny IS NOT NULL",BigDecimal.class,id,id);
        var reserved=db.queryForMap(refs+"SELECT COALESCE(sum(r.reserved_cny*f.weight),0) AS amount,COALESCE(sum(GREATEST(r.reserved_calls-(SELECT count(*) FROM llm_spend s WHERE s.request_id=r.request_id),0)),0) AS calls,count(*) FILTER(WHERE r.state='pending') AS pending,count(*) FILTER(WHERE r.violation) AS violations FROM refs f JOIN llm_cost_request r ON r.task_id=f.id",id,id);
        int attempts=db.queryForObject(refs+"SELECT count(*) FROM refs f JOIN llm_cost_request r ON r.task_id=f.id JOIN llm_spend s USING(request_id)",Integer.class,id,id);
        // Money already settled for a pending request must not also remain in its upper-bound reservation.
        BigDecimal pendingSettled=db.queryForObject(refs+"SELECT COALESCE(sum(s.cost_cny*f.weight),0) FROM refs f JOIN llm_cost_request r ON r.task_id=f.id JOIN llm_spend s USING(request_id) WHERE r.state='pending' AND s.status='SETTLED'",BigDecimal.class,id,id);
        var result=new LinkedHashMap<String,Object>();result.put("settledCny",settled);
        result.put("reservedCny",money(reserved.get("amount")).subtract(pendingSettled).max(BigDecimal.ZERO));
        result.put("reservedCalls",reserved.get("calls"));result.put("pendingRequests",reserved.get("pending"));
        result.put("violations",reserved.get("violations"));result.put("attempts",attempts);return result;
    }

    public Map<String,Object> inspect(String id) {
        if(!id.matches("ct_[a-f0-9]{32}"))throw denied();
        return tx.execute(s->{
            var rows=db.queryForList("SELECT * FROM llm_cost_task WHERE id=? FOR UPDATE",id);
            if(rows.isEmpty())throw denied();
            reconcile(id);var task=rows.getFirst();var view=new LinkedHashMap<String,Object>(totals(id));
            view.put("taskId",id);view.put("alias",task.get("alias"));view.put("owner",task.get("owner_user"));
            view.put("group",task.get("traffic_group"));view.put("state",task.get("state"));view.put("mode",task.get("mode"));
            view.put("budgetCny",task.get("budget_cny"));view.put("maxCalls",task.get("max_calls"));view.put("traceId",task.get("trace_id"));
            try {view.put("policy",json.readTree(task.get("policy").toString()));}catch(Exception error){throw new IllegalStateException(error);}
            view.put("bills",db.queryForList("SELECT s.*,r.purpose FROM llm_spend s JOIN llm_cost_request r USING(request_id) WHERE r.task_id=? ORDER BY s.created_at",id));
            view.put("sharedAllocations",db.queryForList("SELECT s.attempt_id,s.request_id,s.cost_cny,s.status,a.weight,s.cost_cny*a.weight AS allocated_cny,r.purpose,r.task_id AS maintenance_task_id FROM llm_cost_task_share a JOIN llm_cost_request r ON r.task_id=a.source_task_id JOIN llm_spend s USING(request_id) WHERE a.target_task_id=? ORDER BY s.created_at",id));
            view.put("costState",((Number)view.get("pendingRequests")).intValue()>0?"pending":((Number)view.get("attempts")).intValue()==0?"unobserved":"settled");return view;
        });
    }

    public void finish(String id,String state,String trace) {
        if(!Set.of("succeeded","failed","cancelled").contains(state) || !trace.matches("[a-f0-9]{0,32}"))throw denied();
        db.update("UPDATE llm_cost_task SET state=?,trace_id=CASE WHEN ?='' THEN trace_id ELSE ? END,finished_at=COALESCE(finished_at,now()) WHERE id=? AND state='running'",state,trace,trace,id);
    }
    private static BigDecimal money(Object value){return new BigDecimal(value.toString());}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Gateway.GatewayException denied(){return new Gateway.GatewayException(ErrorCode.COST_SCOPE_DENIED,ErrorCode.COST_SCOPE_DENIED.message());}
}
