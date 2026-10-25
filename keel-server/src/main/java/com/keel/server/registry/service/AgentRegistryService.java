package com.keel.server.registry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
import com.keel.server.memory.MemoryService;
import com.keel.server.provisioning.ResourceLedger;
import com.keel.server.provisioning.ResourceNames;
import com.keel.server.registry.mapper.AgentInstanceMapper;
import com.keel.server.registry.mapper.AgentMapper;
import com.keel.server.registry.mapper.AgentVersionMapper;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentInstance;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentRegistryService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentInstanceMapper instanceMapper;
    private final ResourceLedger ledger;
    private final ObjectMapper json;
    private final WizardCatalog wizardCatalog;
    @org.springframework.beans.factory.annotation.Autowired
    private SelfCheckRecords selfCheckRecords;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ElementEvidence elementEvidence;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryService memories;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VersionEvidence versionEvidence;

    public AgentRegistryService(AgentMapper agentMapper, AgentVersionMapper versionMapper,
                                AgentInstanceMapper instanceMapper, ResourceLedger ledger, ObjectMapper json,
                                WizardCatalog wizardCatalog) {
        this.agentMapper = agentMapper;
        this.versionMapper = versionMapper;
        this.instanceMapper = instanceMapper;
        this.ledger = ledger;
        this.json = json;
        this.wizardCatalog = wizardCatalog;
    }

    /**
     * 分类写在 manifest 的 metadata.category，老数据按技术文档里的名单识别。
     * 名单和 manifest 都对不上时分类为 null，业务/研发筛选里不出现。
     */
    public List<String> namesInEnv(String env) {
        if (env == null || env.isBlank() || "all".equals(env)) {
            return List.of();
        }
        return versionMapper.agentNamesInEnv(env);
    }

    public PageResult<AgentSummary> page(String env, String category, AgentStatus status, String q, int page, int size) {
        var scope = env == null || env.isBlank() ? "all" : env;
        var query = agentsOnly().eq(status != null, Agent::getStatus, status);
        if (!"all".equals(scope)) {
            var names = versionMapper.agentNamesInEnv(scope);
            if (names.isEmpty()) {
                return new PageResult<>(page, size, 0, List.of());
            }
            query.in(Agent::getName, names);
        }
        if (StringUtils.hasText(q)) {
            var pattern = q.trim();
            query.and(w -> w.like(Agent::getName, pattern).or().like(Agent::getDisplayName, pattern));
        }
        var versions = versionMapper.latestByAgent(scope).stream()
                .collect(Collectors.toMap(AgentVersion::getAgentName, Function.identity(), (left, right) -> left));
        query.orderByDesc(Agent::getCreatedAt).orderByAsc(Agent::getName);
        var matched = new ArrayList<AgentSummary>();
        for (var agent : agentMapper.selectList(query)) {
            var version = versions.get(agent.getName());
            var summary = AgentSummary.of(agent, version, null, readManifest(version));
            if (category != null && !"all".equals(category) && !category.equals(summary.category())) {
                continue;
            }
            matched.add(summary);
        }
        int from = Math.max(0, (page - 1) * size);
        if (from >= matched.size()) {
            return new PageResult<>(page, size, matched.size(), List.of());
        }
        int to = Math.min(matched.size(), from + size);
        return new PageResult<>(page, size, matched.size(), List.copyOf(matched.subList(from, to)));
    }

    public String invokeEndpoint(String name) {
        require(name);
        var versions = versionMapper.listByAgent(name);
        var manifest = readManifest(versions.isEmpty() ? null : versions.get(0));
        var endpoint = manifest == null ? "" : manifest.path("spec").path("runtime").path("endpoint").asText("");
        if (endpoint.isBlank() || endpoint.contains("/builtin/agents/")) {
            return "http://echo-agent.keel-system.svc.cluster.local:8000";
        }
        return endpoint;
    }

    public JsonNode manifestOrEmpty(String name) {
        var agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getName, name));
        if (agent == null || !Agent.KIND_AGENT.equals(agent.getKind())) {
            return null;
        }
        var versions = versionMapper.listByAgent(name);
        return readManifest(versions.isEmpty() ? null : versions.get(0));
    }

    /** Latest registered configuration in the Key's exact environment; never borrow another environment. */
    public String defaultModelForEnv(String name,String env) {
        var manifest=versionMapper.listByAgent(name).stream().filter(v->env.equals(v.getEnv()))
                .findFirst().map(this::readManifest).orElse(null);
        return manifest==null?"":manifest.path("spec").path("models").path("default").asText("");
    }

    public AgentDetail detail(String name) {
        return readDetail(name, true);
    }

    /** Stored registration and resource checks for control operations; no external observation reads. */
    public AgentDetail registeredDetail(String name) {
        return readDetail(name, false);
    }

    private AgentDetail readDetail(String name, boolean evidence) {
        var agent = require(name);
        var versions = versionMapper.listByAgent(name);
        var latest = versions.isEmpty() ? null : versions.get(0);
        var manifest = readManifest(latest);
        var env = latest == null ? null : latest.getEnv();
        var instances = instanceMapper.selectList(new LambdaQueryWrapper<AgentInstance>()
                .eq(AgentInstance::getAgentName, name));
        if (env != null) {
            instances = instances.stream().filter(row -> env.equals(row.getEnv())).toList();
        }
        var ready = instances.stream().filter(AgentInstance::isReady).count();
        var instanceLabel = instances.isEmpty() ? null : ready + "/" + instances.size();
        var resources = List.<AgentDetail.ResourceRow>of();
        if (ledger != null && env != null) {
            resources = ledger.list(name, env).stream()
                    .map(row -> new AgentDetail.ResourceRow(row.type(),
                            ResourceNames.display(row.type(), name, env, row.externalId(), manifest),
                            row.status()))
                    .toList();
        }
        var detail = AgentDetail.of(agent, latest, instanceLabel, instances, manifest, resources)
                .withVersions(versionRows(name, versions));
        if (evidence && memories != null && manifest != null) {
            var counts = memories.counts(name);
            detail = detail.withMemory(AgentDetail.memoryStatus(agent, manifest, counts.user(), counts.task(), counts.experience()));
            var policies=jdbc.query("SELECT retention_days FROM agent_history_policy WHERE agent_name=? AND env=?",
                (rs,n)->rs.getObject(1,Integer.class),name,detail.summary().env());
            if(!policies.isEmpty() && detail.memory()!=null) {
                var enabled=false;
                for(var tool:manifest.path("spec").path("tools"))if("keel.history.search".equals(tool.path("name").asText()))enabled=true;
                enabled=enabled && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_tool_grant WHERE agent_name=? AND tool_name='keel.history.search' AND status='ACTIVE')",Boolean.class,name));
                var memory=detail.memory();
                detail=detail.withMemory(new AgentDetail.Memory("开启 · "+(policies.getFirst()==null?"保留至删除":policies.getFirst()+" 天"),
                    enabled?"跨会话原文回查":"跨会话回查未接入",memory.task(),memory.experience()));
            }
        }
        if (evidence && elementEvidence != null) detail = elementEvidence.apply(detail);
        return selfCheckRecords == null ? detail : selfCheckRecords.apply(detail);
    }

    public ElementEvidence.Report elements(String name) {
        return elementEvidence.report(registeredDetail(name));
    }

    private List<AgentDetail.VersionRow> versionRows(String name, List<AgentVersion> versions) {
        if (versionEvidence == null) return versions.stream().map(AgentDetail.VersionRow::of).toList();
        return versionEvidence.attach(name, versions);
    }

    public Map<String, Object> nameCheck(String name) {
        var rejection = AgentNames.rejection(name);
        if (rejection != null) {
            return Map.of("available", false, "reason", rejection);
        }
        var taken = agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getName, name)) > 0;
        if (taken) {
            return Map.of("available", false, "reason", "已被占用");
        }
        return Map.of("available", true);
    }

    public ManifestPreview.Result preview(JsonNode form) {
        var tools = wizardCatalog == null ? java.util.List.<WizardTools.Spec>of() : wizardCatalog.resolve(form);
        var knowledge = wizardCatalog == null ? java.util.List.<String>of() : wizardCatalog.resolveKnowledge(form);
        return RegisterManifest.render(form, 8080, tools, knowledge);
    }

    public List<AgentSummary> listAll() {
        return summaries(agentsOnly().orderByAsc(Agent::getName));
    }

    /** 运行总览表格：注册时间从新到旧，相同时间按 name 升序。 */
    public List<AgentSummary> listNewestFirst() {
        return summaries(agentsOnly().orderByDesc(Agent::getCreatedAt).orderByAsc(Agent::getName));
    }

    private List<AgentSummary> summaries(LambdaQueryWrapper<Agent> query) {
        var versions = versionMapper.latestByAgent("all").stream()
                .collect(Collectors.toMap(AgentVersion::getAgentName, Function.identity(), (left, right) -> left));
        var instances = instanceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(AgentInstance::getAgentName));
        return agentMapper.selectList(query).stream().map(agent -> {
            var rows = instances.getOrDefault(agent.getName(), List.of());
            var ready = rows.stream().filter(AgentInstance::isReady).count();
            var label = rows.isEmpty() ? null : ready + "/" + rows.size();
            var version = versions.get(agent.getName());
            return AgentSummary.of(agent, version, label, readManifest(version));
        }).toList();
    }

    private Agent require(String name) {
        var agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getName, name));
        if (agent == null || !Agent.KIND_AGENT.equals(agent.getKind()) || agent.isRemoved()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        return agent;
    }

    private JsonNode readManifest(AgentVersion version) {
        if (version == null || version.getManifestJson() == null) {
            return null;
        }
        try {
            return json.readTree(version.getManifestJson());
        } catch (Exception e) {
            return null;
        }
    }

    private static LambdaQueryWrapper<Agent> agentsOnly() {
        return new LambdaQueryWrapper<Agent>().eq(Agent::getKind, Agent.KIND_AGENT).eq(Agent::isRemoved, false);
    }
}
