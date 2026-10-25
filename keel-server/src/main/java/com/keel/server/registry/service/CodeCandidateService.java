package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.prompt.PromptCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Candidate configuration is mutable; released version snapshots are immutable. */
@Service
public class CodeCandidateService {
    @org.springframework.beans.factory.annotation.Autowired
    private AgentBridgeCredentials bridgeCredentials;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ManifestValidator validator;
    private final PromptCatalog catalog;
    private final AuditStore audit;
    private final AgentEndpointClient endpoints;
    private final String metaBridgeToken;

    public CodeCandidateService(JdbcTemplate jdbc, ObjectMapper json, ManifestValidator validator,
                                PromptCatalog catalog, AuditStore audit, AgentEndpointClient endpoints,
                                @org.springframework.beans.factory.annotation.Value("${KEEL_META_AGENT_TOKEN:}") String metaBridgeToken) {
        this.jdbc = jdbc; this.json = json; this.validator = validator;
        this.catalog = catalog; this.audit = audit;
        this.endpoints = endpoints; this.metaBridgeToken = metaBridgeToken;
    }

    @Transactional
    public Map<String, String> update(String name, JsonNode body) {
        var actor = ConsolePrincipal.requireUsername();
        catalog.authorize(name);
        var env = body.path("env").asText();
        if (!"staging".equals(env)) throw invalid("候选环境须为 staging；dev/test 已停用");
        // Serialize candidate writes and reject stale clients rather than overwriting their edits.
        jdbc.queryForList("SELECT name FROM agent WHERE name=? FOR UPDATE", name);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eval_run WHERE agent_name=? AND state IN ('QUEUED','RUNNING'))", Boolean.class, name)))
            throw invalid("评测进行中不能修改候选配置");
        var rows = jdbc.queryForList("SELECT manifest_json::text AS manifest,manifest_hash FROM agent_version WHERE agent_name=? AND env=? ORDER BY released_at DESC,id DESC LIMIT 1", name, env);
        if (rows.isEmpty() || !body.path("expectedHash").asText().equals(rows.getFirst().get("manifest_hash")))
            throw invalid("候选基线已变化，请重新读取智能体配置");
        JsonNode prior;
        try { prior = json.readTree((String) rows.getFirst().get("manifest")); }
        catch (Exception error) { throw invalid("当前智能体清单无效"); }
        var candidate = body.path("manifest");
        var comparison = prior;
        if (body.path("reconcileRuntime").asBoolean(false)) {
            requireReconciliationAdmin(ConsolePrincipal.current());
            var endpoint = prior.path("spec").path("runtime").path("endpoint").asText();
            var actual = endpoints.manifest(endpoint, bridgeCredentials != null ? bridgeCredentials.token(name)
                    : "meta-agent".equals(name) ? metaBridgeToken : null);
            var unavailable = new HashSet<>(jdbc.queryForList(
                    "SELECT name FROM tool WHERE scope<>'PRIVATE' OR owner_agent IS DISTINCT FROM ?", String.class, name));
            comparison = CodeRuntimeReconciliation.align(prior, candidate, actual, unavailable);
            var baselines = jdbc.queryForList("""
                    SELECT e.result::text FROM release_record r JOIN eval_run e ON e.run_id=r.ci_run_url
                    WHERE r.agent_name=? AND r.env='prod' ORDER BY r.created_at DESC,r.id DESC LIMIT 1
                    """, String.class, name);
            if (!baselines.isEmpty()) {
                try {
                    var gate = (ObjectNode) json.readTree(baselines.getFirst()).path("gate").deepCopy();
                    gate.put("maxRegression", gate.path("maxRegression").asDouble() / 100);
                    CodeRuntimeReconciliation.requireGate(gate, candidate.path("spec").path("eval").path("gate"));
                } catch (KeelException error) { throw error; }
                catch (Exception error) { throw invalid("生产门禁证据无效"); }
            }
        }
        if (hasNewAnalysisTools(comparison,candidate))
            comparison=authorizedAnalysisComparison(comparison,candidate,analysisScopeGranted(name,env));
        comparison=authorizedHistoryComparison(name,env,comparison,candidate);
        requireCompatible(comparison, candidate);
        if (candidate.equals(prior)) return Map.of("env", env, "manifestHash", body.path("expectedHash").asText());
        var declaredTools = new HashSet<String>();
        prior.path("spec").path("tools").forEach(tool -> declaredTools.add(tool.path("name").asText()));
        validator.validate(candidate, declaredTools);
        for (var skill : candidate.path("spec").path("skills")) {
            var granted = jdbc.queryForObject("SELECT count(*) FROM agent_skill_grant WHERE agent_name=? AND skill_name=? AND version=? AND status='GRANTED'",
                    Integer.class, name, skill.path("name").asText(), skill.path("version").asInt());
            if (granted == null || granted != 1) throw invalid("候选技能须先获得对应版本授权");
        }
        var text = candidate.toString();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
        jdbc.update("""
                INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,released_by,released_at)
                VALUES (?,'v0',?,?::jsonb,?,?,clock_timestamp())
                ON CONFLICT(agent_name,env,version) DO UPDATE SET manifest_json=EXCLUDED.manifest_json,
                    manifest_hash=EXCLUDED.manifest_hash,released_at=EXCLUDED.released_at,image=NULL,gate_run_id=NULL,released_by=EXCLUDED.released_by
                """, name, env, text, hash, actor);
        audit.append(name, env, "config.change", "mid", "allowed", "agent:" + name, null, actor,
                Map.of("version", "v0"));
        return Map.of("env", env, "manifestHash", hash);
    }

    private JsonNode authorizedHistoryComparison(String agent,String env,JsonNode prior,JsonNode candidate) {
        var added=new java.util.HashMap<String,JsonNode>();
        candidate.path("spec").path("tools").forEach(t->{
            if(added.put(t.path("name").asText(),t)!=null)throw invalid("工具声明重复");
        });
        prior.path("spec").path("tools").forEach(t->added.remove(t.path("name").asText()));
        if(!added.containsKey("assistant.history.search") && !added.containsKey("keel.history.search"))return prior;
        requireReconciliationAdmin(ConsolePrincipal.current());
        if(!"keel-assistant".equals(agent) || !"staging".equals(env)
            || !added.keySet().equals(java.util.Set.of("assistant.history.search","keel.history.search")))
            throw invalid("只允许本助理staging完整接入两项历史只读工具");
        var actor=ConsolePrincipal.current();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM agent_history_policy p JOIN agent a ON a.name=p.agent_name
              WHERE p.agent_name=? AND p.env=? AND a.owner_org=?)
              AND EXISTS(SELECT 1 FROM agent_tool_grant WHERE agent_name=? AND tool_name='keel.history.search'
                AND version_range='1.0.0' AND status='ACTIVE')
            """,Boolean.class,agent,env,actor.org(),agent)))throw invalid("须先授权共享历史工具");
        for(var tool:added.values())
            if(tool.size()!=4 || !"read".equals(tool.path("access").asText()) || !"low".equals(tool.path("risk").asText())
                || !"none".equals(tool.path("approval").asText()))throw invalid("历史工具必须为低风险只读");
        ObjectNode aligned=prior.deepCopy();
        // Preserve prior definitions: requireCompatible still rejects edits to any existing tool.
        // Tool order is significant to the existing compatibility contract; match only their positions.
        var ordered=aligned.withObject("/spec").putArray("tools");
        for(var tool:candidate.path("spec").path("tools")) {
            var key=tool.path("name").asText();JsonNode original=null;
            for(var old:prior.path("spec").path("tools"))if(key.equals(old.path("name").asText()))original=old;
            ordered.add(original==null?added.get(key):original);
        }
        return aligned;
    }

    static void requireReconciliationAdmin(ConsolePrincipal principal) {
        if (principal == null || principal.readOnly() || !"USER".equals(principal.mode())
                || !"ADMIN".equals(principal.platformRole()))
            throw new KeelException(ErrorCode.AUTH_CONSOLE_FORBIDDEN, "运行清单对齐须由管理员明确发起");
    }

    private static final Map<String,String> ANALYSIS_TOOLS=Map.of(
        "assistant.tasks.create","write","assistant.tasks.get","read","assistant.tasks.list","read",
        "assistant.tasks.prepare","write","assistant.tasks.supply","write",
        "assistant.tasks.cancel","write","assistant.tasks.retry","write",
        "keel.tasks.command","write","keel.tasks.execution","read");

    private static boolean hasNewAnalysisTools(JsonNode prior,JsonNode candidate){
        var old=new HashSet<String>();prior.path("spec").path("tools").forEach(t->old.add(t.path("name").asText()));
        for(var tool:candidate.path("spec").path("tools"))
            if(ANALYSIS_TOOLS.containsKey(tool.path("name").asText()) && !old.contains(tool.path("name").asText()))return true;
        return false;
    }

    private boolean analysisScopeGranted(String name,String env){
        var actor=ConsolePrincipal.current();
        if(!"keel-assistant".equals(name)||!"staging".equals(env)||actor==null||actor.readOnly()
            ||!"USER".equals(actor.mode())||!"ADMIN".equals(actor.platformRole()))return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM assistant_analysis_scope s JOIN agent a ON a.name=s.agent_name
              WHERE s.agent_name='keel-assistant' AND s.environment='staging' AND s.enabled AND s.owner_org=? AND a.owner_org=?)
              AND (SELECT count(DISTINCT tool_name) FROM agent_tool_grant WHERE agent_name='keel-assistant'
                AND tool_name IN ('keel.tasks.command','keel.tasks.execution') AND version_range='1.0.0' AND status='ACTIVE')=2
            """,Boolean.class,actor.org(),actor.org()));
    }

    static JsonNode authorizedAnalysisComparison(JsonNode prior,JsonNode candidate,boolean authorized){
        if(!authorized||!"keel-assistant".equals(prior.path("metadata").path("name").asText()))throw invalid("任务工具声明须先由真实用户明确授权");
        var proposed=new java.util.HashMap<String,JsonNode>();
        for(var tool:candidate.path("spec").path("tools"))
            if(proposed.put(tool.path("name").asText(),tool)!=null)throw invalid("任务工具声明重复");
        for(var tool:prior.path("spec").path("tools")){
            if(!tool.equals(proposed.remove(tool.path("name").asText())))throw invalid("已有工具权限不可变化");
        }
        if(!proposed.keySet().equals(ANALYSIS_TOOLS.keySet()))throw invalid("只允许完整加入本版固定九项任务工具");
        for(var entry:proposed.entrySet()){
            var tool=entry.getValue();
            if(tool.size()!=4||!ANALYSIS_TOOLS.get(entry.getKey()).equals(tool.path("access").asText())
                ||!"low".equals(tool.path("risk").asText())||!"none".equals(tool.path("approval").asText()))throw invalid("任务工具授权范围不匹配");
        }
        ObjectNode aligned=prior.deepCopy();aligned.withObject("/spec").set("tools",candidate.path("spec").path("tools").deepCopy());return aligned;
    }

    static void requireCompatible(JsonNode prior, JsonNode candidate) {
        if (!candidate.isObject() || !"code".equals(prior.path("spec").path("runtime").path("type").asText("code")))
            throw invalid("只支持代码智能体候选配置");
        var oldBoundary = boundary(prior);
        var newBoundary = boundary(candidate);
        // Removing an application fallback only narrows configuration; keep gateway grants untouched.
        var oldModels=oldBoundary.path("spec").path("models");
        var newModels=newBoundary.path("spec").path("models");
        if ((!newModels.has("fallback") || (newModels.path("fallback").isArray() && newModels.path("fallback").isEmpty()))
                && prior.path("spec").path("models").path("default").equals(candidate.path("spec").path("models").path("default"))) {
            ((ObjectNode)oldModels).remove("fallback");
            ((ObjectNode)newModels).remove("fallback");
        }
        if (!oldBoundary.equals(newBoundary))
            throw invalid("身份、权限、运行地址、预算及护栏变更须走各自授权流程");
        var prompts = candidate.path("spec").path("prompts");
        if (!"langfuse".equals(prompts.path("source").asText()) || !prompts.path("items").isArray())
            throw invalid("候选提示词须由 Langfuse 管理");
        var names = new HashSet<String>();
        for (var prompt : prompts.path("items")) {
            if (!prompt.path("name").asText().matches("[a-z][a-z0-9-]{0,38}[a-z0-9]")
                    || !List.of("text", "chat").contains(prompt.path("type").asText())
                    || !names.add(prompt.path("name").asText())) throw invalid("候选提示词声明无效");
        }
        if (candidate.path("spec").has("skills") && !candidate.path("spec").path("skills").isArray())
            throw invalid("候选技能声明无效");
        var skills = new HashSet<String>();
        for (var skill : candidate.path("spec").path("skills")) {
            if (!skill.path("name").asText().matches("[a-z][a-z0-9-]{0,62}[a-z0-9]")
                    || !skill.path("version").isIntegralNumber() || skill.path("version").asInt() < 1
                    || !skills.add(skill.path("name").asText())) throw invalid("候选技能声明无效");
        }
    }

    private static JsonNode boundary(JsonNode manifest) {
        ObjectNode copy = manifest.deepCopy();
        ObjectNode spec = copy.withObject("/spec");
        // capabilities are declarations for element checks, not authz or budget boundaries.
        spec.remove(List.of("prompts", "skills", "capabilities"));
        spec.withObject("/eval").remove(List.of("dataset", "runner"));
        // Adding the explicit default does not turn a workflow into an autonomous agent.
        if (!spec.has("kind")) spec.put("kind", "workflow");
        spec.withObject("/models").remove("default");
        return copy;
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }
}
