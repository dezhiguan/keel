package com.keel.server.tasks;

import com.fasterxml.jackson.databind.*;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.R;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@RestController
public class AnalysisTaskController {
    private final AnalysisTasks tasks;private final JdbcTemplate db;private final AuditStore audit;private final ObjectMapper json;
    public AnalysisTaskController(AnalysisTasks tasks,JdbcTemplate db,AuditStore audit,ObjectMapper json){this.tasks=tasks;this.db=db;this.audit=audit;this.json=json;}
    @PostMapping("/api/v1/tools/keel.tasks.command/invoke")
    public R<Map<String,Object>> command(@RequestBody JsonNode body,@RequestHeader("X-Keel-Env") String env,
        @RequestHeader(value="X-Keel-Trace-Id",defaultValue="") String trace){
        requireService(env,"keel.tasks.command","write");
        var result=tasks.command(body,trace.matches("[a-f0-9]{32}")?trace:null);
        audit.append("keel-assistant","staging","tool.call","low","allowed","keel.tasks.command",trace,null,Map.of());
        return R.ok(result);
    }
    @PostMapping("/api/v1/tools/keel.tasks.execution/invoke")
    public R<Map<String,Object>> execution(@RequestBody JsonNode body,@RequestHeader("X-Keel-Env") String env,
        @RequestHeader(value="X-Keel-Trace-Id",defaultValue="") String trace){
        requireService(env,"keel.tasks.execution","read");
        body.fieldNames().forEachRemaining(k->{if(!Set.of("_authorization","run_id","phase").contains(k))throw TaskContext.denied();});
        return R.ok(tasks.execution(body.path("_authorization").asText(""),trace,body.path("run_id").asText(""),body.path("phase").asText("load")));
    }
    private void requireService(String env,String tool,String access){
        var actor=ConsolePrincipal.current();
        if(actor==null || !"SERVICE".equals(actor.mode()) || !"keel-assistant".equals(actor.username()) || !"staging".equals(env))throw TaskContext.denied();
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM agent WHERE name='keel-assistant' AND status<>'RETIRED') AND EXISTS(SELECT 1 FROM agent_resource WHERE agent_name='keel-assistant' AND env='staging' AND status='ACTIVE') AND NOT EXISTS(SELECT 1 FROM agent_resource WHERE agent_name='keel-assistant' AND env='staging' AND status<>'ACTIVE')",Boolean.class)))throw TaskContext.denied();
        var manifests=db.queryForList("SELECT manifest_json::text AS manifest FROM agent_version WHERE agent_name='keel-assistant' AND env='staging' ORDER BY released_at DESC,id DESC LIMIT 1");
        boolean declared=false;
        if(!manifests.isEmpty())try{for(var t:json.readTree((String)manifests.getFirst().get("manifest")).path("spec").path("tools"))
            if(tool.equals(t.path("name").asText()) && access.equals(t.path("access").asText()) && "low".equals(t.path("risk").asText()))declared=true;
        }catch(Exception invalid){throw TaskContext.denied();}
        if(!declared || !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tool_grant WHERE agent_name='keel-assistant' AND tool_name=? AND status='ACTIVE')",Boolean.class,tool)))throw TaskContext.denied();
    }
    @PutMapping("/api/v1/agents/{name}/task-scope")
    @Transactional
    public R<Map<String,Object>> scope(@PathVariable String name,@RequestBody JsonNode body){
        var actor=ConsolePrincipal.current();
        if(actor==null || actor.readOnly() || !"USER".equals(actor.mode()) || !"ADMIN".equals(actor.platformRole())
            || !"keel-assistant".equals(name) || !"staging".equals(body.path("environment").asText()) || !body.path("enabled").isBoolean())throw TaskContext.denied();
        body.fieldNames().forEachRemaining(k->{if(!Set.of("environment","enabled").contains(k))throw TaskContext.invalid("未知任务授权字段");});
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM agent WHERE name=? AND owner_org=?)",Boolean.class,name,actor.org())))throw TaskContext.denied();
        boolean enabled=body.path("enabled").asBoolean();
        // Audit is synchronous and inside the transaction; a write failure rolls back the scope.
        audit.append(name,"staging","tool.grant","high","allowed","assistant.analysis.scope",null,actor.username(),Map.of("enabled",enabled));
        db.update("INSERT INTO assistant_analysis_scope(agent_name,environment,owner_org,enabled,granted_by) VALUES (?,'staging',?,?,?) ON CONFLICT(agent_name,environment) DO UPDATE SET enabled=excluded.enabled,granted_by=excluded.granted_by,updated_at=clock_timestamp()",name,actor.org(),enabled,actor.username());
        for(String tool:List.of("keel.tasks.command","keel.tasks.execution"))db.update("INSERT INTO agent_tool_grant(agent_name,tool_name,version_range,granted_by,status) VALUES (?,?,'1.0.0',?,?) ON CONFLICT(agent_name,tool_name,version_range) DO UPDATE SET status=excluded.status,granted_by=excluded.granted_by",name,tool,actor.username(),enabled?"ACTIVE":"REVOKED");
        if(!enabled){
            db.update("UPDATE assistant_analysis_task SET state='waiting_authorization',error='任务授权已撤销',updated_at=clock_timestamp() WHERE state='queued'");
            db.update("UPDATE assistant_analysis_task SET state='cancel_requested',error='任务授权已撤销；停止后续步骤，模型调用未确认停止',updated_at=clock_timestamp() WHERE state IN ('running','validating')");
        }
        return R.ok(Map.of("agent",name,"environment","staging","enabled",enabled,"taskType","analysis"));
    }
}
