package com.keel.server.registry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.keel.server.registry.model.entity.AgentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersion> {
    @Select("""
            SELECT id, agent_name, version, env, manifest_json::text AS manifest_json, manifest_hash,
                   image, gate_run_id, released_by, released_at
            FROM agent_version
            WHERE agent_name = #{agentName}
            ORDER BY released_at DESC
            """)
    List<AgentVersion> listByAgent(String agentName);

    @Select("""
            SELECT DISTINCT agent_name FROM agent_version WHERE env = #{env}
            """)
    List<String> agentNamesInEnv(String env);
}
