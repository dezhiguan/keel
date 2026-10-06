package com.keel.server.tool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.common.PageResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tool registry backed by the platform tables, including tools declared on agent manifests. */
@Service
public class ToolRegistryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ToolRegistryService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public PageResult<Map<String, Object>> page(String scope, String status, String risk, String env, int page, int size) {
        syncFromManifests();
        var scopeEnv = env == null || env.isBlank() || "all".equals(env) ? "all" : env;
        var declared = dependentCounts(scopeEnv);
        var owners = "all".equals(scopeEnv) ? java.util.Set.<String>of() : ownersInEnv(scopeEnv);
        var rows = jdbc.query("""
                SELECT t.name, t.scope, t.owner_agent, t.owner_org, t.provider, t.status,
                       t.replaced_by, t.deprecate_deadline::date AS deadline,
                       v.version, v.description, v.access, v.risk, p.name AS policy
                FROM tool t
                LEFT JOIN LATERAL (
                    SELECT version, description, access, risk, approval_policy_id
                    FROM tool_version WHERE tool_name = t.name ORDER BY created_at DESC LIMIT 1
                ) v ON true
                LEFT JOIN approval_policy p ON p.id = v.approval_policy_id
                ORDER BY t.name
                """, (rs, n) -> row(rs));
        var filtered = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            if (!"all".equals(scope) && !scope.equals(row.get("scope"))) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(row.get("status"))) {
                continue;
            }
            if (risk != null && !risk.isBlank() && !risk.equals(row.get("risk"))) {
                continue;
            }
            var name = String.valueOf(row.get("name"));
            var owner = row.get("ownerAgent") == null ? null : String.valueOf(row.get("ownerAgent"));
            if (!visibleInEnv(scopeEnv, name, owner, declared, owners)) {
                continue;
            }
            row.put("dependentCount", declared.getOrDefault(name, 0));
            row.put("calls24h", 0);
            filtered.add(row);
        }
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(filtered.size(), from + size);
        var items = from >= filtered.size() ? List.<Map<String, Object>>of() : filtered.subList(from, to);
        return new PageResult<>(page, size, filtered.size(), items);
    }

    public Map<String, Object> detail(String name) {
        syncFromManifests();
        var rows = jdbc.query("""
                SELECT t.name, t.scope, t.owner_agent, t.owner_org, t.provider, t.status,
                       t.replaced_by, t.deprecate_deadline::date AS deadline,
                       v.version, v.description, v.access, v.risk, v.schema_json::text AS schema_json, p.name AS policy
                FROM tool t
                LEFT JOIN LATERAL (
                    SELECT version, description, access, risk, approval_policy_id, schema_json
                    FROM tool_version WHERE tool_name = t.name ORDER BY created_at DESC LIMIT 1
                ) v ON true
                LEFT JOIN approval_policy p ON p.id = v.approval_policy_id
                WHERE t.name = ?
                """, (rs, n) -> {
            var row = row(rs);
            row.put("schemaJson", readJson(rs.getString("schema_json")));
            return row;
        }, name);
        if (rows.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var body = rows.getFirst();
        var dependents = dependentsOf(name);
        body.put("dependents", dependents);
        body.put("dependentCount", dependents.size());
        body.put("calls24h", 0);
        body.put("versions", versions(name));
        return body;
    }

    public void register(JsonNode body) {
        var name = text(body, "name");
        if (name.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "工具名不能为空");
        }
        var scope = upper(text(body, "scope"), "PRIVATE");
        var access = upper(text(body, "access"), "READ");
        var risk = upper(text(body, "risk"), "LOW");
        var provider = text(body, "provider");
        if (provider.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "provider 不能为空");
        }
        var ownerAgent = blankToNull(text(body, "ownerAgent"));
        var ownerOrg = text(body, "ownerOrg");
        if (ownerOrg.isBlank()) {
            ownerOrg = "平台";
        }
        var description = text(body, "description");
        if (description.isBlank()) {
            description = name;
        }
        var count = jdbc.queryForObject("SELECT count(*) FROM tool WHERE name = ?", Integer.class, name);
        if (count != null && count > 0) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "工具名已存在");
        }
        var schema = body.path("schemaJson").isMissingNode() ? json.createObjectNode() : body.path("schemaJson");
        jdbc.update("""
                INSERT INTO tool (name, scope, owner_agent, owner_org, provider, status)
                VALUES (?, ?, ?, ?, ?, 'REGISTERED')
                ON CONFLICT (name) DO NOTHING
                """, name, scope, ownerAgent, ownerOrg, provider);
        jdbc.update("""
                INSERT INTO tool_version (tool_name, version, schema_json, description, access, risk, breaking)
                VALUES (?, 'v1', ?::jsonb, ?, ?, ?, false)
                ON CONFLICT (tool_name, version) DO NOTHING
                """, name, schema.toString(), description, access, risk);
    }

    public List<String> publish(String name, JsonNode body) {
        requireName(name);
        var status = jdbc.queryForObject("SELECT status FROM tool WHERE name = ?", String.class, name);
        if ("RETIRED".equals(status)) {
            throw new KeelException(ErrorCode.TOOL_RETIRED, ErrorCode.TOOL_RETIRED.message());
        }
        var rejection = breakingRejection(body.path("breaking").asBoolean(false));
        if (rejection != null) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, rejection);
        }
        var version = text(body, "version");
        if (version.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "版本不能为空");
        }
        var existing = jdbc.queryForObject(
                "SELECT count(*) FROM tool_version WHERE tool_name = ? AND version = ?", Integer.class, name, version);
        if (existing != null && existing > 0) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "版本已存在");
        }
        var current = detail(name);
        var description = text(body, "description");
        if (description.isBlank()) {
            description = String.valueOf(current.get("description"));
        }
        var access = upper(text(body, "access"), String.valueOf(current.get("access")));
        var risk = upper(text(body, "risk"), String.valueOf(current.get("risk")));
        var schema = body.path("schemaJson").isMissingNode() || body.path("schemaJson").isNull()
                ? json.valueToTree(current.get("schemaJson")) : body.path("schemaJson");
        jdbc.update("""
                INSERT INTO tool_version (tool_name, version, schema_json, description, access, risk, breaking)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, false)
                """, name, version, schema.toString(), description, access, risk);
        if ("REGISTERED".equals(status)) {
            jdbc.update("UPDATE tool SET status = 'ONLINE', updated_at = now() WHERE name = ?", name);
        }
        return dependentsOf(name).stream().map(row -> String.valueOf(row.get("agent"))).distinct().toList();
    }

    static String breakingRejection(boolean breaking) {
        return breaking ? "破坏兼容必须用新名字注册，不能在原名上发版" : null;
    }

    public void deprecate(String name, String replacedBy, String deadline) {
        requireName(name);
        if (replacedBy == null || replacedBy.isBlank() || deadline == null || deadline.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "废弃必须给出替代工具和截止日期");
        }
        jdbc.update("""
                UPDATE tool SET status = 'DEPRECATED', replaced_by = ?, deprecate_deadline = ?::timestamptz, updated_at = now()
                WHERE name = ?
                """, replacedBy, deadline, name);
    }

    public void retire(String name) {
        requireName(name);
        var envs = dependentsOf(name).stream().map(row -> String.valueOf(row.get("env"))).toList();
        if (blocksRetire(envs)) {
            throw new KeelException(ErrorCode.TOOL_HAS_PROD_DEPENDENTS, ErrorCode.TOOL_HAS_PROD_DEPENDENTS.message());
        }
        jdbc.update("UPDATE tool SET status = 'RETIRED', updated_at = now() WHERE name = ?", name);
    }

    static boolean blocksRetire(List<String> envs) {
        return envs.stream().anyMatch("prod"::equals);
    }

    /** A tool belongs to an environment when a manifest there declares it, or its owner is registered there. */
    static boolean visibleInEnv(String env, String toolName, String owner, Map<String, Integer> declared, java.util.Set<String> owners) {
        if (env == null || env.isBlank() || "all".equals(env)) {
            return true;
        }
        if (declared.containsKey(toolName)) {
            return true;
        }
        return owner != null && owners.contains(owner);
    }

    private void syncFromManifests() {
        var manifests = jdbc.query("""
                SELECT a.name AS agent, a.owner_org, v.env, v.manifest_json::text AS manifest
                FROM agent a
                JOIN agent_version v ON v.agent_name = a.name
                WHERE a.kind = 'AGENT'
                """, (rs, n) -> new ManifestRow(rs.getString("agent"), rs.getString("owner_org"), rs.getString("env"), rs.getString("manifest")));
        for (var row : manifests) {
            for (var declared : declaredTools(json, row.manifest())) {
                jdbc.update("""
                        INSERT INTO tool (name, scope, owner_agent, owner_org, provider, status)
                        VALUES (?, ?, ?, ?, ?, 'ONLINE')
                        ON CONFLICT (name) DO NOTHING
                        """, declared.name(), declared.scope(), row.agent(),
                        row.ownerOrg() == null || row.ownerOrg().isBlank() ? "平台" : row.ownerOrg(),
                        "manifest://" + row.agent() + "/" + declared.name());
                jdbc.update("""
                        INSERT INTO tool_version (tool_name, version, schema_json, description, access, risk, breaking)
                        VALUES (?, ?, '{}'::jsonb, ?, ?, ?, false)
                        ON CONFLICT (tool_name, version) DO NOTHING
                        """, declared.name(), declared.version(), declared.description(), declared.access(), declared.risk());
            }
        }
    }

    static List<DeclaredTool> declaredTools(ObjectMapper json, String manifest) {
        if (manifest == null || manifest.isBlank()) {
            return List.of();
        }
        try {
            var tools = json.readTree(manifest).path("spec").path("tools");
            if (!tools.isArray()) {
                return List.of();
            }
            var declared = new ArrayList<DeclaredTool>();
            for (var tool : tools) {
                var name = tool.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                var scope = tool.path("shared").asBoolean(false) ? "SHARED" : "PRIVATE";
                var version = tool.path("version").asText("v1");
                var description = tool.path("description").asText(name);
                declared.add(new DeclaredTool(name, scope, version, description,
                        upper(tool.path("access").asText(""), "READ"),
                        upper(tool.path("risk").asText(""), "LOW")));
            }
            return declared;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Integer> dependentCounts(String env) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var row : declarationRows()) {
            if (!"all".equals(env) && !env.equals(row.env())) {
                continue;
            }
            for (var tool : declaredTools(json, row.manifest())) {
                counts.merge(tool.name(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private java.util.Set<String> ownersInEnv(String env) {
        var names = new java.util.HashSet<String>();
        for (var row : declarationRows()) {
            if (env.equals(row.env())) {
                names.add(row.agent());
            }
        }
        return names;
    }

    private List<Map<String, Object>> dependentsOf(String toolName) {
        var dependents = new ArrayList<Map<String, Object>>();
        for (var row : jdbc.query("""
                SELECT a.name AS agent, a.status::text AS status, v.env, v.manifest_json::text AS manifest
                FROM agent a
                JOIN agent_version v ON v.agent_name = a.name
                WHERE a.kind = 'AGENT'
                """, (rs, n) -> new AgentManifest(rs.getString("agent"), rs.getString("status"), rs.getString("env"), rs.getString("manifest")))) {
            var names = declaredTools(json, row.manifest()).stream().map(DeclaredTool::name).toList();
            if (!names.contains(toolName)) {
                continue;
            }
            var item = new LinkedHashMap<String, Object>();
            item.put("agent", row.agent());
            item.put("env", row.env());
            item.put("agentStatus", row.status());
            item.put("declaredInManifest", true);
            item.put("calls30d", 0);
            dependents.add(item);
        }
        return dependents;
    }

    private List<ManifestRow> declarationRows() {
        return jdbc.query("""
                SELECT a.name AS agent, a.owner_org, v.env, v.manifest_json::text AS manifest
                FROM agent a JOIN agent_version v ON v.agent_name = a.name WHERE a.kind = 'AGENT'
                """, (rs, n) -> new ManifestRow(rs.getString("agent"), rs.getString("owner_org"), rs.getString("env"), rs.getString("manifest")));
    }

    private List<Map<String, Object>> versions(String name) {
        return jdbc.query("""
                SELECT version, description, breaking, created_at FROM tool_version
                WHERE tool_name = ? ORDER BY created_at DESC
                """, (rs, n) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("version", rs.getString("version"));
            item.put("change", rs.getString("description"));
            item.put("breaking", rs.getBoolean("breaking"));
            var at = rs.getTimestamp("created_at");
            item.put("at", at == null ? null : at.toInstant().toString());
            return item;
        }, name);
    }

    private void requireName(String name) {
        var count = jdbc.queryForObject("SELECT count(*) FROM tool WHERE name = ?", Integer.class, name);
        if (count == null || count == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static Map<String, Object> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        var item = new LinkedHashMap<String, Object>();
        item.put("name", rs.getString("name"));
        item.put("description", rs.getString("description"));
        item.put("scope", rs.getString("scope"));
        item.put("ownerAgent", rs.getString("owner_agent"));
        item.put("ownerOrg", rs.getString("owner_org"));
        item.put("provider", rs.getString("provider"));
        item.put("access", rs.getString("access"));
        item.put("risk", rs.getString("risk"));
        item.put("version", rs.getString("version"));
        item.put("status", rs.getString("status"));
        item.put("approvalPolicy", rs.getString("policy"));
        item.put("replacedBy", rs.getString("replaced_by"));
        var deadline = rs.getDate("deadline");
        item.put("deprecateDeadline", deadline == null ? null : deadline.toString());
        return item;
    }

    private static String text(JsonNode body, String field) {
        return body.path(field).asText("");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String upper(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.toUpperCase(Locale.ROOT);
    }

    record DeclaredTool(String name, String scope, String version, String description, String access, String risk) {}

    private record ManifestRow(String agent, String ownerOrg, String env, String manifest) {}

    private record AgentManifest(String agent, String status, String env, String manifest) {}
}
