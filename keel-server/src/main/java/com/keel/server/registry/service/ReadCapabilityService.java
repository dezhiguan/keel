package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.provisioning.SecretWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Explicit capability approval, separate from ordinary candidate configuration edits. */
@Service
public class ReadCapabilityService {
    static final List<String> TOOLS=List.of("assistant.knowledge.search","assistant.employees.list","assistant.employees.detail",
            "keel.employees.list","keel.employees.detail","keel.knowledge.authorize");
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WizardCatalog catalog;
    private final SecretWriter secrets;private final AuditStore audit;
    public ReadCapabilityService(JdbcTemplate jdbc,ObjectMapper json,WizardCatalog catalog,SecretWriter secrets,AuditStore audit){
        this.jdbc=jdbc;this.json=json;this.catalog=catalog;this.secrets=secrets;this.audit=audit;
    }
    @Transactional
    public Map<String,String> approve(String agent,JsonNode request){
        var user=ConsolePrincipal.current();CodeCandidateService.requireReconciliationAdmin(user);
        request.fieldNames().forEachRemaining(field->{if(!Set.of("env","expectedHash","knowledgeBases").contains(field))throw denied("未知授权参数");});
        var env=request.path("env").asText("");
        if(!List.of("dev","staging").contains(env))throw denied("仅开放dev/staging");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent WHERE name=? AND owner_org=?)",Boolean.class,agent,user.org())))throw denied("只能批准本组织智能体");
        jdbc.queryForList("SELECT name FROM agent WHERE name=? FOR UPDATE",agent);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eval_run WHERE agent_name=? AND state IN ('QUEUED','RUNNING'))",Boolean.class,agent)))throw denied("评测期间禁止变更能力");
        var prior=jdbc.queryForList("SELECT manifest_json::text AS manifest,manifest_hash FROM agent_version WHERE agent_name=? AND env=? ORDER BY released_at DESC,id DESC LIMIT 1",agent,env);
        if(prior.isEmpty() || !request.path("expectedHash").asText().equals(prior.getFirst().get("manifest_hash")))throw denied("配置基线变化，请重新读取");
        var form=json.createObjectNode();form.set("knowledgeBases",request.path("knowledgeBases"));
        var kbs=catalog.resolveKnowledge(form);
        if(kbs.size()>1)throw denied("最多允许明确授权的一个知识库");
        for(var tool:TOOLS.subList(3,6)) {
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tool_grant g JOIN tool t ON t.name=g.tool_name WHERE g.agent_name=? AND g.tool_name=? AND g.status='ACTIVE' AND t.scope='SHARED' AND t.status='ONLINE')",Boolean.class,agent,tool)))throw denied("须先通过共享工具授权："+tool);
        }
        ObjectNode manifest;
        try {manifest=(ObjectNode)json.readTree(String.valueOf(prior.getFirst().get("manifest")));}
        catch(Exception error){throw denied("登记清单无效");}
        var spec=manifest.withObject("/spec");
        if(spec.path("knowledge").size()>0)throw denied("已有知识范围，须单独审查升级");
        if(spec.path("tools").size()>0) {
            var existing=new HashSet<String>();
            for(var tool:spec.path("tools")) {
                if(!TOOLS.contains(tool.path("name").asText()) || !"read".equals(tool.path("access").asText()) || !"low".equals(tool.path("risk").asText()))
                    throw denied("已有工具不符合固定只读升级边界");
                existing.add(tool.path("name").asText());
            }
            if(!existing.equals(new HashSet<>(TOOLS)) || spec.path("tools").size()!=TOOLS.size())throw denied("已有工具集合不符合固定只读边界");
        }
        var tools=spec.putArray("tools");TOOLS.forEach(name->tools.addObject().put("name",name).put("access","read").put("risk","low").put("approval","none"));
        if(!kbs.isEmpty())spec.putArray("knowledge").addObject().put("kb",kbs.getFirst());
        var text=manifest.toString();String hash;
        try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
        // Dedicated provisioning preserves model keys, budget and existing Secret configuration.
        if(!kbs.isEmpty())secrets.upgradeKnowledge(agent,env,kbs);
        jdbc.update("""
          INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,released_by,released_at)
          VALUES (?,'v0',?,?::jsonb,?,?,clock_timestamp())
          ON CONFLICT(agent_name,env,version) DO UPDATE SET manifest_json=excluded.manifest_json,manifest_hash=excluded.manifest_hash,
            released_by=excluded.released_by,released_at=excluded.released_at,image=NULL,gate_run_id=NULL
          """,agent,env,text,hash,user.username());
        audit.append(agent,env,"config.change","mid","allowed","readonly.capabilities",null,user.username(),Map.of());
        return Map.of("agent",agent,"env",env,"manifestHash",hash);
    }
    private static KeelException denied(String message){return new KeelException(ErrorCode.SERVER_INVALID_PARAM,message);}
}
