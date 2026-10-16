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
        var detail=registry.detail(agent);
        if(detail.summary().status()==AgentStatus.RETIRED || !env.equals(detail.summary().env())
                || detail.resources().isEmpty() || detail.resources().stream().anyMatch(r->!"ACTIVE".equals(r.status())))throw denied();
        var declared=false;
        for(var tool:registry.manifestOrEmpty(agent).path("spec").path("tools"))
            if(name.equals(tool.path("name").asText()) && "read".equals(tool.path("access").asText()) && "low".equals(tool.path("risk").asText()))declared=true;
        if(!declared || !granted(agent, name, "git.pr.diff".equals(name))) {
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
        if(!"git.pr.diff".equals(name)) {
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
