package com.keel.server.cost;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.litellm.LiteLlmClient;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class TaskCostService {
    private final JdbcTemplate db;private final ObjectMapper json;private final LiteLlmClient gateway;
    private final CostPolicies policies;private final AgentRegistryService registry;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public TaskCostService(JdbcTemplate db,ObjectMapper json,LiteLlmClient gateway,CostPolicies policies,AgentRegistryService registry){
        this.db=db;this.json=json;this.gateway=gateway;this.policies=policies;this.registry=registry;
    }
    public String org(String agent,String env) {
        var rows=db.queryForList("SELECT a.owner_org FROM agent a WHERE a.name=? AND a.status<>'RETIRED' AND EXISTS(SELECT 1 FROM agent_resource r WHERE r.agent_name=a.name AND r.env=? AND r.status='ACTIVE')",agent,env);
        if(rows.isEmpty() || !Set.of("staging","prod","test","dev").contains(env))throw denied();
        return Objects.toString(rows.getFirst().get("owner_org"),"");
    }
    public ObjectNode policy(String agent,String env){return policies.effective(agent,env,org(agent,env));}
    public ObjectNode memoryPolicy(String agent,String env) {
        var rows=db.query("SELECT owner_org FROM agent WHERE name=?",(rs,n)->rs.getString(1),agent);
        return policies.effective(agent,env,rows.isEmpty()?"":rows.getFirst());
    }
    public boolean supported(String agent) {
        try {
            var endpoint=registry.invokeEndpoint(agent).replaceAll("/$","")+"/v1/manifest";
            var r=http.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            return r.statusCode()==200 && json.readTree(r.body()).path("costControl").path("version").asInt()==1;
        }catch(Exception unavailable){return false;}
    }
    public ObjectNode open(String agent,String env,String owner,String group,int inputChars) {
        var policy=policy(agent,env);
        BigDecimal cap=policy.path("baseCostCny").decimalValue().add(policy.path("perKInputCny").decimalValue().multiply(BigDecimal.valueOf(Math.max(1,inputChars)/1000.0))).min(policy.path("maxCostCny").decimalValue());
        var body=json.createObjectNode().put("alias",agent+"-"+env).put("owner",owner).put("group",group).put("budgetCny",cap);
        body.set("policy",policy);var context=(ObjectNode)gateway.taskCost("open",body);
        db.update("INSERT INTO task_cost_scope(task_id,agent_name,env,owner_user,traffic_group,context) VALUES(?,?,?,?,?,?::jsonb)",context.path("taskId").asText(),agent,env,owner,group,context.toString());
        return context;
    }
    public ObjectNode serviceOpen(String agent,String env,int inputChars,String evaluation) {
        service(agent);if(!supported(agent))throw denied();return open(agent,env,"service:"+agent,evaluation.isBlank()?"service":"eval",inputChars);
    }
    public void finish(String id,String state,String trace) {
        if(id==null || id.isBlank())return;
        if(!trace.matches("[a-f0-9]{0,32}"))throw denied();
        gateway.taskCost("finish",Map.of("taskId",id,"state",state,"traceId",trace));
        db.update("UPDATE task_cost_scope SET trace_id=CASE WHEN trace_id='' THEN ? ELSE trace_id END WHERE task_id=?",trace,id);
    }
    public void serviceFinish(String agent,String env,JsonNode body) {
        service(agent);scope(agent,env,body.path("taskId").asText(),false);
        String root=db.queryForObject("SELECT trace_id FROM task_cost_scope WHERE task_id=?",String.class,body.path("taskId").asText());
        if(root!=null&&!root.isBlank()&&!root.equals(body.path("traceId").asText()))return;
        finish(body.path("taskId").asText(),body.path("state").asText(),body.path("traceId").asText(""));
    }
    public void serviceBind(String agent,String env,JsonNode body) {
        service(agent);scope(agent,env,body.path("taskId").asText(),false);
        String trace=body.path("traceId").asText();if(!trace.matches("[a-f0-9]{32}"))throw denied();
        db.update("UPDATE task_cost_scope SET trace_id=? WHERE task_id=? AND trace_id=''",trace,body.path("taskId").asText());
    }
    public ObjectNode byTrace(String agent,String env,String trace) {
        var rows=db.query("SELECT context::text FROM task_cost_scope WHERE agent_name=? AND env=? AND trace_id=? ORDER BY created_at DESC LIMIT 1",(rs,n)->rs.getString(1),agent,env,trace);
        try{return rows.isEmpty()?null:(ObjectNode)json.readTree(rows.getFirst());}catch(Exception error){throw new IllegalStateException(error);}
    }
    public void attachMessage(String agent,String env,String owner,String trace,long userId) {
        db.update("UPDATE task_cost_scope SET user_message_id=? WHERE agent_name=? AND env=? AND owner_user=? AND trace_id=?",userId,agent,env,owner,trace);
    }
    public ObjectNode memory(String agent,String env,String owner,List<Map<String,Object>> messages) {
        var ids=messages.stream().filter(m->"user".equals(m.get("role"))).map(m->Long.parseLong(m.get("id").toString())).toList();
        if(ids.isEmpty())return null;
        var params=new ArrayList<Object>(List.of(agent,env,owner));params.addAll(ids);
        var roots=db.queryForList("SELECT task_id FROM task_cost_scope WHERE agent_name=? AND env=? AND owner_user=? AND user_message_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY task_id",params.toArray());
        if(roots.isEmpty()&&!"enforce".equals(memoryPolicy(agent,env).path("mode").asText()))return null;
        var policy=policy(agent,env);
        var body=json.createObjectNode().put("alias",agent+"-"+env).put("owner",owner).put("group","memory").put("budgetCny",policy.path("maxCostCny").decimalValue());
        policy.put("allocationRule","covered-user-turns-equal/v1");policy.set("coveredUserMessageIds",json.valueToTree(ids));
        body.set("policy",policy);var shares=body.putArray("shares");
        BigDecimal weight=roots.isEmpty()?BigDecimal.ZERO:BigDecimal.ONE.divide(BigDecimal.valueOf(roots.size()),15,java.math.RoundingMode.DOWN),remaining=BigDecimal.ONE;
        for(int i=0;i<roots.size();i++){var part=i==roots.size()-1?remaining:weight;shares.addObject().put("taskId",roots.get(i).get("task_id").toString()).put("weight",part);remaining=remaining.subtract(part);}
        var context=(ObjectNode)gateway.taskCost("open",body);
        db.update("INSERT INTO task_cost_scope(task_id,agent_name,env,owner_user,traffic_group,context,allocated_memory) VALUES(?,?,?,?,?,?::jsonb,?)",context.path("taskId").asText(),agent,env,owner,"memory",context.toString(),!roots.isEmpty());
        return context;
    }
    public Map<String,Object> inspect(String agent,String env,String id) {
        scope(agent,env,id,true);
        var view=json.convertValue(gateway.taskCost("inspect",Map.of("taskId",id)),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
        String owner=Objects.toString(view.remove("owner"),"");
        String trace=Objects.toString(db.queryForObject("SELECT trace_id FROM task_cost_scope WHERE task_id=?",String.class,id),"");
        view.put("traceId",trace);
        var background=db.queryForList("SELECT task_id,state FROM assistant_analysis_task WHERE agent_name=? AND environment=? AND owner_user=? AND parent_trace_id=? AND parent_trace_id<>''",agent,env,owner,trace);
        view.put("backgroundTasks",background);
        view.put("deliveryState",background.stream().anyMatch(t->!Set.of("succeeded","failed","cancelled").contains(t.get("state")))?"running":background.stream().anyMatch(t->Set.of("failed","cancelled").contains(t.get("state")))?"failed":view.get("state"));
        Integer pending=db.queryForObject("""
            SELECT count(*) FROM session_memory_job j JOIN session_message m ON m.session_id=j.session_id
            AND m.owner_user=j.owner_user AND m.agent_name=j.agent_name JOIN task_cost_scope t ON t.user_message_id=m.id
            WHERE t.task_id=? AND j.status IN ('pending','running') AND j.desired_id>=m.id AND COALESCE(j.batch_start_id,0)<=m.id
            """,Integer.class,id);
        view.put("maintenancePending",pending);
        if((pending!=null&&pending>0 || "running".equals(view.get("deliveryState"))) && "settled".equals(view.get("costState")))view.put("costState","pending_work");
        return view;
    }
    public List<Map<String,Object>> list(String agent,String env) {
        var p=principal();String owner="SERVICE".equals(p.mode())?null:p.userId();
        if("SERVICE".equals(p.mode()))service(agent);org(agent,env);
        return db.queryForList("SELECT task_id,traffic_group,trace_id,created_at FROM task_cost_scope WHERE agent_name=? AND env=? AND (?::text IS NULL OR owner_user=?) AND NOT allocated_memory ORDER BY created_at DESC LIMIT 100",agent,env,owner,owner);
    }
    public void requireContext(String alias,JsonNode policy){gateway.taskCost("required",Map.of("alias",alias,"required","enforce".equals(policy.path("mode").asText()),"maxCostCny",policy.path("maxCostCny").decimalValue(),"maxCalls",policy.path("maxCalls").asInt()));}
    public List<Map<String,Object>> affected(JsonNode body) {
        String scope=body.path("scope").asText(),key=body.path("key").asText();
        return db.queryForList("SELECT DISTINCT a.name,r.env FROM agent a JOIN agent_resource r ON r.agent_name=a.name WHERE a.status<>'RETIRED' AND r.status='ACTIVE' AND (?='platform' OR (?='org' AND a.owner_org=?) OR (?='env' AND r.env=?) OR (?='agent' AND a.name||':'||r.env=?))",scope,scope,key,scope,key,scope,key);
    }
    private void scope(String agent,String env,String id,boolean user) {
        var p=principal();if("SERVICE".equals(p.mode()))service(agent);
        var rows=db.queryForList("SELECT owner_user FROM task_cost_scope WHERE task_id=? AND agent_name=? AND env=?",id,agent,env);
        if(rows.isEmpty() || (user&&!"SERVICE".equals(p.mode())&&!p.userId().equals(rows.getFirst().get("owner_user"))))throw denied();
        org(agent,env);
    }
    public static void service(String agent){var p=principal();if(!"SERVICE".equals(p.mode())||!agent.equals(p.userId()))throw denied();}
    public static ConsolePrincipal principal(){var p=ConsolePrincipal.current();if(p==null||"PREVIEW".equals(p.mode()))throw denied();return p;}
    public static KeelException denied(){return new KeelException(ErrorCode.COST_SCOPE_DENIED,ErrorCode.COST_SCOPE_DENIED.message());}
}
