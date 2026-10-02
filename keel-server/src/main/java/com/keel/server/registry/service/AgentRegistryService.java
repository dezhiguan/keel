package com.keel.server.registry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.keel.server.common.PageResult;
import com.keel.server.registry.mapper.AgentMapper;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.enums.AgentStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class AgentRegistryService {
    private final AgentMapper agentMapper;

    public AgentRegistryService(AgentMapper agentMapper) {
        this.agentMapper = agentMapper;
    }

    /**
     * TODO(P1-4): filter by env (needs agent_version) and category (needs the manifest); both are accepted but ignored.
     */
    public PageResult<AgentSummary> page(AgentStatus status, String q, int page, int size) {
        var query = agentsOnly().eq(status != null, Agent::getStatus, status);
        if (StringUtils.hasText(q)) {
            var pattern = q.trim();
            query.and(w -> w.like(Agent::getName, pattern).or().like(Agent::getDisplayName, pattern)
                    .or().like(Agent::getOwnerUser, pattern));
        }
        var result = agentMapper.selectPage(Page.of(page, size), query.orderByAsc(Agent::getName));
        var items = result.getRecords().stream().map(AgentSummary::of).toList();
        return new PageResult<>(result.getCurrent(), result.getSize(), result.getTotal(), items);
    }

    public List<AgentSummary> listAll() {
        return agentMapper.selectList(agentsOnly().orderByAsc(Agent::getName)).stream().map(AgentSummary::of).toList();
    }

    private static LambdaQueryWrapper<Agent> agentsOnly() {
        return new LambdaQueryWrapper<Agent>().eq(Agent::getKind, Agent.KIND_AGENT);
    }
}
