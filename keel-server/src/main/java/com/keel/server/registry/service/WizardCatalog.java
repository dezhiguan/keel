package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared tools and tool-call approval policies for the create wizard. */
@Service
public class WizardCatalog {
    private final JdbcTemplate jdbc;

    public WizardCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> sharedTools() {
        return jdbc.query("""
                SELECT t.name, t.scope, t.status, v.description, v.access, v.risk, v.version, p.name AS policy
                FROM tool t
                LEFT JOIN LATERAL (
                    SELECT description, access, risk, version, approval_policy_id
                    FROM tool_version WHERE tool_name = t.name ORDER BY created_at DESC LIMIT 1
                ) v ON true
                LEFT JOIN approval_policy p ON p.id = v.approval_policy_id
                WHERE t.scope = 'SHARED' AND t.status <> 'RETIRED'
                ORDER BY t.name
                """, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("scope", rs.getString("scope"));
            row.put("access", rs.getString("access"));
            row.put("risk", rs.getString("risk"));
            row.put("version", rs.getString("version"));
            row.put("status", rs.getString("status"));
            row.put("approvalPolicy", rs.getString("policy"));
            return row;
        });
    }

    public List<Map<String, Object>> approvalPolicies() {
        return jdbc.query("""
                SELECT id::text AS id, name FROM approval_policy
                WHERE subject_type = 'tool.call' ORDER BY name
                """, (rs, n) -> Map.of("id", rs.getString("id"), "name", rs.getString("name")));
    }

    public List<WizardTools.Spec> resolve(JsonNode form) {
        var rows = jdbc.query("""
                SELECT t.name, t.status, v.access, v.risk, v.version
                FROM tool t
                LEFT JOIN LATERAL (
                    SELECT access, risk, version
                    FROM tool_version WHERE tool_name = t.name ORDER BY created_at DESC LIMIT 1
                ) v ON true
                WHERE t.scope = 'SHARED' AND t.status <> 'RETIRED'
                """, (rs, n) -> new WizardTools.Row(rs.getString("name"), rs.getString("status"),
                rs.getString("access"), rs.getString("risk"), rs.getString("version")));
        var policies = jdbc.query("""
                SELECT id::text FROM approval_policy WHERE subject_type = 'tool.call'
                """, (rs, n) -> rs.getString(1));
        return WizardTools.resolve(form, rows, Set.copyOf(policies));
    }
}
