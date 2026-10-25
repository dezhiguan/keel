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

    /** Prefer the newest formal snapshot over an editable v0 draft updated later. */
    @Select("""
            SELECT DISTINCT ON (agent_name) id, agent_name, version, env, manifest_json::text AS manifest_json,
                   manifest_hash, image, gate_run_id, released_by, released_at
            FROM agent_version
            WHERE (#{env} = 'all' OR env = #{env})
            ORDER BY agent_name,
                     CASE WHEN version = 'v0' AND coalesce(gate_run_id, '') = '' THEN 1 ELSE 0 END,
                     released_at DESC, id DESC
            """)
    List<AgentVersion> latestByAgent(String env);
}
