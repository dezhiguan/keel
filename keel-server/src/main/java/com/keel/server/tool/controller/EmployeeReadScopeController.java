package com.keel.server.tool.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.*;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.tool.service.ToolGrantPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class EmployeeReadScopeController {
    private final JdbcTemplate jdbc;private final AuditStore audit;
    public EmployeeReadScopeController(JdbcTemplate jdbc,AuditStore audit){this.jdbc=jdbc;this.audit=audit;}
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/api/v1/agents/{name}/read-scope")
    public R<Map<String,Object>> set(@PathVariable String name,@RequestBody JsonNode body){
        var user=ToolGrantPolicy.requireConsoleWriter(ConsolePrincipal.current());
        var env=body.path("environment").asText("");var org=body.path("targetOrg").asText("");
        if(!"ADMIN".equals(user.platformRole()) || !user.org().equals(org))throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN,"只能授权当前管理员所属组织");
        var fields=Set.of("environment","targetOrg","enabled");
        body.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"未知授权参数");});
        if(!Set.of("dev","staging").contains(env) || !body.path("enabled").isBoolean()
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent WHERE name=? AND kind='AGENT' AND owner_org=?)",Boolean.class,name,user.org())))
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"授权范围无效");
        jdbc.update("""
          INSERT INTO agent_employee_read_scope(agent_name,environment,target_org,enabled,granted_by) VALUES (?,?,?,?,?)
          ON CONFLICT(agent_name,environment,target_org) DO UPDATE SET enabled=excluded.enabled,granted_by=excluded.granted_by,updated_at=now()
          """,name,env,org,body.path("enabled").asBoolean(),user.username());
        audit.append(name,env,"tool.grant","high","allowed","employee.read.scope",null,user.username(),Map.of());
        return R.ok(Map.of("agent",name,"environment",env,"targetOrg",org,"enabled",body.path("enabled").asBoolean()));
    }
}
