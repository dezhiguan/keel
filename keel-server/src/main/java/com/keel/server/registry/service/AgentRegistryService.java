package com.keel.server.registry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
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

import java.util.List;
import java.util.Map;

@Service
public class AgentRegistryService {
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final AgentInstanceMapper instanceMapper;
    private final ObjectMapper json;

    public AgentRegistryService(AgentMapper agentMapper, AgentVersionMapper versionMapper,
                                AgentInstanceMapper instanceMapper, ObjectMapper json) {
        this.agentMapper = agentMapper;
        this.versionMapper = versionMapper;
        this.instanceMapper = instanceMapper;
        this.json = json;
    }

    /**
     * category 不在 V1 表上，manifest schema 里也没有这个字段。筛选值不是 all 时返回空页。
     * TODO(P1-4): 分类规则还没定，不要为了筛选去改已经执行过的 V1。
     */
    public PageResult<AgentSummary> page(String env, String category, AgentStatus status, String q, int page, int size) {
        if (category != null && !"all".equals(category)) {
            return new PageResult<>(page, size, 0, List.of());
        }
        var query = agentsOnly().eq(status != null, Agent::getStatus, status);
        if (env != null && !"all".equals(env)) {
            var names = versionMapper.agentNamesInEnv(env);
            if (names.isEmpty()) {
                return new PageResult<>(page, size, 0, List.of());
            }
            query.in(Agent::getName, names);
        }
        if (StringUtils.hasText(q)) {
            var pattern = q.trim();
            query.and(w -> w.like(Agent::getName, pattern).or().like(Agent::getDisplayName, pattern)
                    .or().like(Agent::getOwnerUser, pattern));
        }
        var result = agentMapper.selectPage(Page.of(page, size), query.orderByAsc(Agent::getName));
        var items = result.getRecords().stream().map(agent -> AgentSummary.of(agent, null, null)).toList();
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), items);
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

    public AgentDetail detail(String name) {
        var agent = require(name);
        var versions = versionMapper.listByAgent(name);
        var latest = versions.isEmpty() ? null : versions.get(0);
        var instances = instanceMapper.selectList(new LambdaQueryWrapper<AgentInstance>()
                .eq(AgentInstance::getAgentName, name));
        var ready = instances.stream().filter(AgentInstance::isReady).count();
        var instanceLabel = instances.isEmpty() ? null : ready + "/" + instances.size();
        return AgentDetail.of(agent, latest, instanceLabel, instances, readManifest(latest));
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
        return ManifestPreview.render(form);
    }

    public List<AgentSummary> listAll() {
        return agentMapper.selectList(agentsOnly().orderByAsc(Agent::getName)).stream().map(AgentSummary::of).toList();
    }

    private Agent require(String name) {
        var agent = agentMapper.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getName, name));
        if (agent == null || !Agent.KIND_AGENT.equals(agent.getKind())) {
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
        return new LambdaQueryWrapper<Agent>().eq(Agent::getKind, Agent.KIND_AGENT);
    }
}
