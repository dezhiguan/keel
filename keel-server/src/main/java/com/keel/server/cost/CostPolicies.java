package com.keel.server.cost;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class CostPolicies {
    private final JdbcTemplate db;private final ObjectMapper json;
    private static final Set<String> FIELDS=Set.of("mode","maxCostCny","maxCalls","baseCostCny","perKInputCny","memoryBatchTurns","memoryMaxWaitSeconds","contextDedupe","evidenceDedupe");
    public CostPolicies(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public ObjectNode effective(String agent,String env,String org) {
        ObjectNode merged=json.createObjectNode();var versions=new ArrayList<String>();
        for(var layer:List.of(List.of("platform","*"),List.of("org",org),List.of("env",env),List.of("agent",agent+":"+env))) {
            var rows=db.queryForList("SELECT revision,config FROM task_cost_policy WHERE scope=? AND scope_key=? ORDER BY revision DESC LIMIT 1",layer.get(0),layer.get(1));
            if(rows.isEmpty())continue;
            var row=rows.getFirst();JsonNode config=parse(row.get("config"));
            validate(config);
            if("enforce".equals(merged.path("mode").asText())&&"observe".equals(config.path("mode").asText()))throw conflict();
            for(String field:List.of("maxCostCny","maxCalls"))if(merged.has(field)&&config.has(field)&&config.path(field).decimalValue().compareTo(merged.path(field).decimalValue())>0)throw conflict();
            config.fields().forEachRemaining(e->merged.set(e.getKey(),e.getValue()));
            versions.add(layer.get(0)+":"+layer.get(1)+"@"+row.get("revision"));
        }
        if(!merged.has("maxCostCny") || !merged.has("maxCalls") || !merged.has("mode"))throw conflict();
        merged.put("version",String.join("/",versions));return merged;
    }
    @Transactional
    public Map<String,Object> publish(JsonNode body,String actor) {
        String scope=body.path("scope").asText(),key=body.path("key").asText();int revision=body.path("revision").asInt(-1);
        if(!Set.of("platform","org","env","agent").contains(scope)||key.isBlank()||key.length()>200||revision<0||!body.path("config").isObject())throw conflict();
        if(scope.equals("platform")&&!key.equals("*"))throw conflict();
        validate(body.path("config"));
        // Advisory transaction lock also protects creation where no previous row exists.
        db.execute("SELECT pg_advisory_xact_lock(hashtext('keel-cost-policy'))");
        Integer current=db.queryForObject("SELECT COALESCE(max(revision),0) FROM task_cost_policy WHERE scope=? AND scope_key=?",Integer.class,scope,key);
        if(current!=revision)throw conflict();
        if(!scope.equals("platform")) {
            var root=db.queryForObject("SELECT config::text FROM task_cost_policy WHERE scope='platform' AND scope_key='*' ORDER BY revision DESC LIMIT 1",String.class);
            var top=parse(root);
            for(String field:List.of("maxCostCny","maxCalls"))if(body.path("config").has(field)&&body.path("config").path(field).decimalValue().compareTo(top.path(field).decimalValue())>0)throw conflict();
        }
        db.update("INSERT INTO task_cost_policy(scope,scope_key,revision,config,created_by) VALUES(?,?,?,?::jsonb,?)",scope,key,revision+1,body.path("config").toString(),actor);
        // Resolve every registered chain inside this transaction. A conflicting parent change
        // must roll back, rather than leave a published revision that breaks later reads.
        for(var target:db.queryForList("SELECT DISTINCT a.name,a.owner_org,r.env FROM agent a JOIN agent_resource r ON r.agent_name=a.name WHERE a.status<>'RETIRED' AND r.status='ACTIVE'"))
            effective(target.get("name").toString(),target.get("env").toString(),Objects.toString(target.get("owner_org"),""));
        return Map.of("scope",scope,"key",key,"revision",revision+1);
    }
    public List<Map<String,Object>> history(){return db.queryForList("SELECT scope,scope_key,revision,config,created_by,created_at FROM task_cost_policy ORDER BY created_at DESC LIMIT 100");}
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw conflict();}}
    private static void validate(JsonNode config) {
        var names=config.fieldNames();while(names.hasNext())if(!FIELDS.contains(names.next()))throw conflict();
        if(config.has("mode")&&!Set.of("observe","enforce").contains(config.path("mode").asText()))throw conflict();
        for(String name:List.of("maxCostCny","baseCostCny","perKInputCny"))if(config.has(name)&&(!config.path(name).isNumber()||config.path(name).decimalValue().signum()<=0||config.path(name).decimalValue().compareTo(new BigDecimal("100"))>0))throw conflict();
        for(String name:List.of("maxCalls","memoryBatchTurns","memoryMaxWaitSeconds"))if(config.has(name)&&(!config.path(name).isIntegralNumber()||config.path(name).asInt()<1||config.path(name).asInt()>1000))throw conflict();
        for(String name:List.of("contextDedupe","evidenceDedupe"))if(config.has(name)&&!config.path(name).isBoolean())throw conflict();
    }
    static KeelException conflict(){return new KeelException(ErrorCode.COST_POLICY_CONFLICT,ErrorCode.COST_POLICY_CONFLICT.message());}
}
