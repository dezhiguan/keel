package com.keel.server.tool.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.registry.service.AgentRegistryService;
import com.keel.server.registry.model.enums.AgentStatus;
import com.keel.server.tool.ShiftPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Only explicitly granted, declared low-risk read tools may use this service identity route. */
@RestController
public class ToolInvokeController {
    private final JdbcTemplate jdbc;
    private final AgentRegistryService registry;
    private final AuditStore audit;
    private final ObjectMapper json;
    private final String base;
    private final String token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public ToolInvokeController(JdbcTemplate jdbc, AgentRegistryService registry, AuditStore audit,
                                ObjectMapper json, @Value("${KEEL_GIT_CI_URL:}") String base,
                                @Value("${KEEL_GIT_CI_TOKEN:}") String token) {
        this.jdbc=jdbc; this.registry=registry; this.audit=audit; this.json=json; this.base=base; this.token=token;
    }
    @PostMapping("/api/v1/tools/{name}/invoke")
    public R<JsonNode> invoke(@PathVariable String name,@RequestBody JsonNode body,
                             @RequestHeader("X-Keel-Env") String env,
                             @RequestHeader(value="X-Keel-Trace-Id",defaultValue="") String traceId) {
        var principal=ConsolePrincipal.current();
        if(principal==null || !"SERVICE".equals(principal.mode())) throw denied();
        var agent=principal.username();
        var detail=registry.registeredDetail(agent);
        if(detail.summary().status()==AgentStatus.RETIRED) throw denied();
        var manifests = registeredManifest(agent, env);
        String resourceEnv = resourceEnvironment(env, !manifests.isEmpty(),
                manifests.isEmpty() && "prod".equals(env) && approvedProductionPreview(agent));
        if (!env.equals(resourceEnv)) {
            manifests = registeredManifest(agent, resourceEnv);
        }
        var active = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_resource WHERE agent_name=? AND env=? AND status='ACTIVE')
                  AND NOT EXISTS(SELECT 1 FROM agent_resource WHERE agent_name=? AND env=? AND status<>'ACTIVE')
                """, Boolean.class, agent, resourceEnv, agent, resourceEnv);
        if (manifests.isEmpty() || !Boolean.TRUE.equals(active)) throw denied();
        JsonNode manifest;
        try { manifest = json.readTree(manifests.getFirst()); }
        catch (Exception error) { throw denied(); }
        var declared=false;
        for(var tool:manifest.path("spec").path("tools"))
            if(name.equals(tool.path("name").asText()) && "read".equals(tool.path("access").asText()) && "low".equals(tool.path("risk").asText()))declared=true;
        if(!declared || !granted(agent, name, "git.pr.diff".equals(name) || "ci.log.fetch".equals(name))) {
            audit.append(agent,env,"tool.call","low","denied",name,validTrace(traceId),agent,java.util.Map.of());
            throw denied();
        }
        if("shift.policy".equals(name)) {
            if(!ShiftPolicy.known(body.path("category").asText("").trim())) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "值班口径类别只能是故障、变更、咨询");
            }
            audit.append(agent,env,"tool.call","low","allowed",name,validTrace(traceId),agent,java.util.Map.of("category", body.path("category").asText("").trim(), "count", 1));
            return R.ok(ShiftPolicy.answer(body));
        }
        if("devflow.catalog.search".equals(name)) {
            var query = body.path("query").asText("").trim();
            var rows = jdbc.queryForList("""
                    SELECT t.name, v.description, v.access, v.risk FROM tool t
                    JOIN LATERAL (SELECT description, access, risk FROM tool_version
                        WHERE tool_name=t.name ORDER BY created_at DESC LIMIT 1) v ON true
                    WHERE t.scope='SHARED' AND t.status<>'RETIRED'
                      AND (lower(t.name) LIKE lower(?) OR lower(v.description) LIKE lower(?))
                    ORDER BY t.name LIMIT 20
                    """, "%"+query+"%", "%"+query+"%");
            audit.append(agent,env,"tool.call","low","allowed",name,validTrace(traceId),agent,java.util.Map.of("count",rows.size()));
            return R.ok(json.valueToTree(rows));
        }
        if("devflow.job.read".equals(name) || "devflow.batch.read".equals(name)
                || "keel.gate.report.read".equals(name)) {
            var id = body.path("jobId").asText(body.path("batchId").asText("")).trim();
            if(!id.matches("[A-Za-z0-9_-]{1,32}"))
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"需要有效的 jobId 或 batchId");
            java.util.List<java.util.Map<String,Object>> rows;
            if("devflow.job.read".equals(name)) {
                rows=jdbc.queryForList("SELECT job_id,batch_id,title,status,stage,goal,updated_at FROM devflow_job WHERE job_id=? LIMIT 1",id);
            } else if("devflow.batch.read".equals(name)) {
                rows=jdbc.queryForList("SELECT batch_id,title,pilot_job_id,pilot_passed,created_at FROM devflow_batch WHERE batch_id=? LIMIT 1",id);
            } else {
                rows=jdbc.queryForList("SELECT job_id,kind,ref,sha256,created_at FROM devflow_artifact WHERE job_id=? AND kind='GATE_REPORT' ORDER BY created_at DESC LIMIT 10",id);
            }
            audit.append(agent,env,"tool.call","low","allowed",name,validTrace(traceId),agent,java.util.Map.of("count",rows.size()));
            return R.ok(json.valueToTree(rows));
        }
        if(!"git.pr.diff".equals(name) && !"ci.log.fetch".equals(name)) {
            audit.append(agent,env,"tool.call","low","denied",name,validTrace(traceId),agent,java.util.Map.of());
            throw denied();
        }
        if(base.isBlank() || token.isBlank())throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED,"git-ci 未部署或未配置");
        try {
            var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+"/v1/tools/"+name))
                    .timeout(Duration.ofSeconds(40)).header("Content-Type","application/json").header("Authorization","Bearer "+token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()>=300)throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED,"共享工具执行失败 HTTP "+response.statusCode());
            var result=json.readTree(response.body());
            audit.append(agent,env,"tool.call","low","allowed",name,validTrace(traceId),agent,java.util.Map.of());
            return R.ok(result);
        }catch(KeelException e){throw e;}
        catch(Exception e){throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED,"共享工具服务不可达");}
    }
    private java.util.List<String> registeredManifest(String agent, String env) {
        return jdbc.query("SELECT manifest_json::text FROM agent_version WHERE agent_name=? AND env=? ORDER BY released_at DESC, id DESC LIMIT 1",
                (rs, row) -> rs.getString(1), agent, env);
    }
    static String resourceEnvironment(String env, boolean registered, boolean approvedPreview) {
        if (registered) return env;
        if ("prod".equals(env) && approvedPreview) return "staging";
        throw denied();
    }
    private boolean approvedProductionPreview(String agent) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent a JOIN devflow_execution e ON e.job_id=a.devflow_job_id
                  WHERE a.name=? AND e.state_json->>'phase'='RELEASING'
                    AND e.state_json->'gate'->>'passed'='true'
                    AND e.state_json->>'mergeSha' ~ '^[0-9a-f]{40}$'
                    AND e.state_json->'gate'->>'sha'=e.state_json->'delivery'->>'sha'
                    AND EXISTS(SELECT 1 FROM approval_request r WHERE r.devflow_job_id=a.devflow_job_id
                      AND r.id::text=regexp_replace(e.state_json->>'h4ApprovalId','^ap_','')
                      AND r.agent_name=a.name AND r.payload_digest=e.state_json->'delivery'->>'sha'
                      AND r.devflow_gate='H4' AND r.subject_type='tool.call' AND r.subject_ref='git.pr.merge'
                      AND r.status='APPROVED'))
                """, Boolean.class, agent));
    }
    private boolean granted(String agent, String name, boolean gitCi) {
        var provider = gitCi ? " AND t.provider='git-ci'" : "";
        var allowed = jdbc.queryForObject("""
                SELECT count(*) FROM agent_tool_grant g JOIN tool t ON t.name=g.tool_name
                JOIN LATERAL (SELECT access,risk FROM tool_version WHERE tool_name=t.name ORDER BY created_at DESC LIMIT 1) v ON true
                WHERE g.agent_name=? AND g.tool_name=? AND g.status='ACTIVE' AND t.status<>'RETIRED'
                AND v.access='READ' AND v.risk='LOW'
                """ + provider, Integer.class, agent, name);
        return allowed != null && allowed > 0;
    }
    private static String validTrace(String trace) {return trace.matches("[0-9a-f]{32}")?trace:null;}
    private static KeelException denied() {return new KeelException(ErrorCode.TOOL_NOT_GRANTED,ErrorCode.TOOL_NOT_GRANTED.message());}
}
