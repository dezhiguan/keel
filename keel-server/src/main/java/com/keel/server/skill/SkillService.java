package com.keel.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Skill registry and grants. Granting a skill never writes agent_tool_grant. */
@Service
public class SkillService {
    static final String INSERT_GRANT = """
            INSERT INTO agent_skill_grant (agent_name, skill_name, version, status, granted_by)
            VALUES (?, ?, ?, 'GRANTED', ?)
            ON CONFLICT (agent_name, skill_name)
            DO UPDATE SET version = EXCLUDED.version, status = 'GRANTED', granted_by = EXCLUDED.granted_by
            """;
    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}[a-z0-9]$");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LangfuseClient langfuse;

    public SkillService(JdbcTemplate jdbc, ObjectMapper json, LangfuseClient langfuse) {
        this.jdbc = jdbc;
        this.json = json;
        this.langfuse = langfuse;
    }

    public Map<String, Object> list() {
        var items = new ArrayList<Map<String, Object>>();
        for (var name : jdbc.query("SELECT name FROM skill ORDER BY name", (rs, n) -> rs.getString(1))) {
            items.add(summary(name));
        }
        return Map.of("items", items);
    }

    public Map<String, Object> publish(JsonNode body) {
        var name = body.path("name").asText("").trim();
        if (!NAME.matcher(name).matches()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "技能名无效");
        }
        var owner = body.path("ownerOrg").asText("").trim();
        var description = body.path("description").asText("").trim();
        var instructions = body.path("instructions").asText("").trim();
        if (owner.isBlank() || description.isBlank() || instructions.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "所有者、说明和技能正文都不能为空");
        }
        var source = body.path("source").asText("AUTHORED");
        if (!source.equals("AUTHORED") && !source.equals("EXPERIENCE")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "来源只能是 AUTHORED 或 EXPERIENCE");
        }
        var tools = strings(body.get("tools"));
        for (var tool : tools) {
            var found = jdbc.queryForObject("SELECT count(*) FROM tool WHERE name = ?", Integer.class, tool);
            if (found == null || found == 0) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "依赖工具未注册：" + tool);
            }
        }
        var examples = strings(body.get("examples"));
        var cases = cases(body.get("cases"));
        jdbc.update("""
                INSERT INTO skill (name, owner_org, description, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET owner_org = EXCLUDED.owner_org, description = EXCLUDED.description, source = EXCLUDED.source
                """, name, owner, description, source);
        var version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM skill_version WHERE skill_name = ?", Integer.class, name);
        jdbc.update("""
                INSERT INTO skill_version (skill_name, version, instructions, tools_json, examples_json, cases_json)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """, name, version, instructions, write(tools), write(examples), write(cases));
        return Map.of("name", name, "version", version);
    }

    public Map<String, Object> granted(String agent) {
        requireAgent(agent);
        var items = new ArrayList<Map<String, Object>>();
        jdbc.query("SELECT skill_name, version FROM agent_skill_grant WHERE agent_name = ? AND status = 'GRANTED' ORDER BY skill_name",
                (rs, n) -> items.add(grantRow(rs.getString(1), rs.getInt(2))) , agent);
        return Map.of("items", items);
    }

    public Map<String, Object> grant(String agent, JsonNode body, String grantedBy) {
        requireAgent(agent);
        if (grantedBy == null || grantedBy.isBlank()) {
            throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
        }
        var name = body.path("name").asText("").trim();
        if (!body.path("version").canConvertToInt()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "version 必须是整数");
        }
        var version = body.path("version").asInt();
        var stored = versionRow(name, version);
        if (stored == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "技能版本不存在");
        }
        var merged = mergeCases(agent, name, version, stored.cases());
        jdbc.update(INSERT_GRANT, agent, name, version, grantedBy);
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("version", version);
        result.put("toolsGranted", List.of());
        result.put("casesMerged", merged);
        return result;
    }

    public Map<String, Object> load(String agent, String skill) {
        requireAgent(agent);
        var rows = jdbc.query("SELECT version FROM agent_skill_grant WHERE agent_name = ? AND skill_name = ? AND status = 'GRANTED'",
                (rs, n) -> rs.getInt(1), agent, skill);
        if (rows.isEmpty()) {
            throw new KeelException(ErrorCode.SKILL_NOT_GRANTED, ErrorCode.SKILL_NOT_GRANTED.message());
        }
        var stored = versionRow(skill, rows.getFirst());
        if (stored == null) {
            throw new KeelException(ErrorCode.SKILL_NOT_GRANTED, ErrorCode.SKILL_NOT_GRANTED.message());
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("name", skill);
        body.put("version", stored.version());
        body.put("instructions", stored.instructions());
        body.put("examples", stored.examples());
        body.put("tools", stored.tools());
        return body;
    }

    static String highest(List<String> risks) {
        var rank = 0;
        var label = "LOW";
        for (var risk : risks) {
            var value = "HIGH".equals(risk) ? 3 : "MID".equals(risk) ? 2 : 1;
            if (value > rank) {
                rank = value;
                label = value == 3 ? "HIGH" : value == 2 ? "MID" : "LOW";
            }
        }
        return label;
    }

    private int mergeCases(String agent, String skill, int version, List<Map<String, String>> cases) {
        if (cases.isEmpty()) {
            return 0;
        }
        var dataset = datasetOf(agent);
        if (dataset.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "未声明评测数据集");
        }
        var merged = 0;
        for (var item : cases) {
            langfuse.addSkillCase(dataset, skill, version, item.get("id"), item.get("input"), item.get("expectedOutput"));
            merged++;
        }
        return merged;
    }

    private String datasetOf(String agent) {
        var rows = jdbc.query("SELECT manifest_json::text FROM agent_version WHERE agent_name = ?",
                (rs, n) -> rs.getString(1), agent);
        for (var raw : rows) {
            try {
                var dataset = json.readTree(raw).path("spec").path("eval").path("dataset").asText("");
                if (!dataset.isBlank()) {
                    return dataset;
                }
            } catch (Exception ignored) {
                // The next manifest row may still declare a dataset.
            }
        }
        return "";
    }

    private Map<String, Object> summary(String name) {
        var latest = jdbc.query("""
                SELECT version, tools_json::text FROM skill_version WHERE skill_name = ? ORDER BY version DESC LIMIT 1
                """, (rs, n) -> Map.<String, Object>of("version", rs.getInt(1), "tools", rs.getString(2)), name);
        var meta = jdbc.queryForMap("SELECT owner_org, description, source FROM skill WHERE name = ?", name);
        var tools = readStrings(latest.isEmpty() ? "[]" : String.valueOf(latest.getFirst().get("tools")));
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("version", latest.isEmpty() ? 0 : latest.getFirst().get("version"));
        body.put("ownerOrg", meta.get("owner_org"));
        body.put("description", meta.get("description"));
        body.put("source", meta.get("source"));
        body.put("tools", tools);
        body.put("risk", highest(risksOf(tools)));
        body.put("users", jdbc.query("SELECT agent_name FROM agent_skill_grant WHERE skill_name = ? AND status = 'GRANTED' ORDER BY agent_name",
                (rs, n) -> rs.getString(1), name));
        return body;
    }

    private Map<String, Object> grantRow(String name, int version) {
        var stored = versionRow(name, version);
        var description = jdbc.queryForObject("SELECT description FROM skill WHERE name = ?", String.class, name);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("version", version);
        body.put("description", description);
        body.put("tools", stored == null ? List.of() : stored.tools());
        body.put("risk", highest(risksOf(stored == null ? List.of() : stored.tools())));
        body.put("status", "GRANTED");
        return body;
    }

    private Version versionRow(String name, int version) {
        var rows = jdbc.query("""
                SELECT version, instructions, tools_json::text, examples_json::text, cases_json::text
                FROM skill_version WHERE skill_name = ? AND version = ?
                """, (rs, n) -> new Version(rs.getInt(1), rs.getString(2), readStrings(rs.getString(3)),
                readStrings(rs.getString(4)), readCases(rs.getString(5))), name, version);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<String> risksOf(List<String> tools) {
        var risks = new ArrayList<String>();
        for (var tool : tools) {
            var rows = jdbc.query("SELECT risk FROM tool_version WHERE tool_name = ? ORDER BY created_at DESC LIMIT 1",
                    (rs, n) -> rs.getString(1), tool);
            risks.add(rows.isEmpty() ? "LOW" : rows.getFirst());
        }
        return risks;
    }

    private void requireAgent(String agent) {
        var found = jdbc.queryForObject("SELECT count(*) FROM agent WHERE name = ?", Integer.class, agent);
        if (found == null || found == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
    }

    private List<String> strings(JsonNode node) {
        var values = new ArrayList<String>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                var text = item.asText("").trim();
                if (!text.isBlank() && !values.contains(text)) {
                    values.add(text);
                }
            });
        }
        return values;
    }

    private List<Map<String, String>> cases(JsonNode node) {
        var values = new ArrayList<Map<String, String>>();
        if (node == null || !node.isArray()) {
            return values;
        }
        var index = 0;
        for (var item : node) {
            index++;
            if (!item.isObject()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "用例必须是对象");
            }
            var input = item.path("input").asText("").trim();
            if (input.isBlank()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "用例要有输入");
            }
            var id = item.path("id").asText("").trim();
            var row = new LinkedHashMap<String, String>();
            row.put("id", id.isBlank() ? "case-" + index : id);
            row.put("input", input);
            row.put("expectedOutput", item.path("expectedOutput").asText(""));
            values.add(row);
        }
        return values;
    }

    private List<String> readStrings(String raw) {
        try {
            var values = new ArrayList<String>();
            json.readTree(raw).forEach(item -> values.add(item.asText("")));
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, String>> readCases(String raw) {
        try {
            var values = new ArrayList<Map<String, String>>();
            json.readTree(raw).forEach(item -> {
                var row = new LinkedHashMap<String, String>();
                row.put("id", item.path("id").asText(""));
                row.put("input", item.path("input").asText(""));
                row.put("expectedOutput", item.path("expectedOutput").asText(""));
                values.add(row);
            });
            return values;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Version(int version, String instructions, List<String> tools, List<String> examples, List<Map<String, String>> cases) {}
}
