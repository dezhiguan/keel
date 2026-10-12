package com.keel.server.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcResourceLedger implements ResourceLedger {
    private final JdbcTemplate jdbc;

    public JdbcResourceLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void activate(String agent, String env, String type, String externalId) {
        jdbc.update("""
                INSERT INTO agent_resource (agent_name, env, type, external_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT (agent_name, env, type, external_id)
                DO UPDATE SET status = 'ACTIVE', updated_at = now()
                """, agent, env, type, externalId);
    }

    @Override
    public void mark(String agent, String env, String type, String status) {
        jdbc.update("""
                UPDATE agent_resource SET status = ?, updated_at = now()
                WHERE agent_name = ? AND env = ? AND type = ? AND status = 'ACTIVE'
                """, status, agent, env, type);
    }

    @Override
    public boolean hasActive(String agent, String env) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_resource
                WHERE agent_name = ? AND env = ? AND status = 'ACTIVE'
                """, Integer.class, agent, env);
        return count != null && count > 0;
    }

    @Override
    public java.util.List<String> activeTypes(String agent, String env) {
        return jdbc.query("""
                SELECT type FROM agent_resource
                WHERE agent_name = ? AND env = ? AND status = 'ACTIVE'
                ORDER BY id DESC
                """, (rs, row) -> rs.getString("type"), agent, env);
    }

    @Override
    public java.util.List<StoredResource> list(String agent, String env) {
        if (agent == null || agent.isBlank() || env == null || env.isBlank()) {
            return java.util.List.of();
        }
        return jdbc.query("""
                SELECT type, external_id, status FROM agent_resource
                WHERE agent_name = ? AND env = ?
                ORDER BY CASE type
                    WHEN 'litellm_key' THEN 1
                    WHEN 'secret' THEN 2
                    WHEN 'dataset' THEN 3
                    WHEN 'oauth_client' THEN 4
                    WHEN 'langfuse' THEN 5
                    ELSE 6 END, id
                """, (rs, row) -> new StoredResource(rs.getString("type"), rs.getString("external_id"), rs.getString("status")),
                agent, env);
    }
}
