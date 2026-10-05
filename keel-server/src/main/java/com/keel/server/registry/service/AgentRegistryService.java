package com.keel.server.registry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
     * 分类写在 manifest 的 metadata.category，老数据按技术文档里的名单识别。
     * 名单和 manifest 都对不上时分类为 null，业务/研发筛选里不出现。
     */
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
        var matched = new ArrayList<AgentSummary>();
        for (var agent : agentMapper.selectList(query.orderByAsc(Agent::getName))) {
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
        var versions = versionMapper.latestByAgent("all").stream()
                .collect(Collectors.toMap(AgentVersion::getAgentName, Function.identity(), (left, right) -> left));
        var instances = instanceMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .collect(Collectors.groupingBy(AgentInstance::getAgentName));
        return agentMapper.selectList(agentsOnly().orderByAsc(Agent::getName)).stream().map(agent -> {
            var rows = instances.getOrDefault(agent.getName(), List.of());
            var ready = rows.stream().filter(AgentInstance::isReady).count();
            var label = rows.isEmpty() ? null : ready + "/" + rows.size();
            var version = versions.get(agent.getName());
            return AgentSummary.of(agent, version, label, readManifest(version));
        }).toList();
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
