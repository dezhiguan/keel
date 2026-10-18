package com.keel.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
        var name = body.path("name").asText("").trim().toLowerCase(java.util.Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "技能名只能是小写字母、数字和连字符，例如 cite-from-kb");
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
        var note = body.path("note").asText("").trim();
        var version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM skill_version WHERE skill_name = ?", Integer.class, name);
        if (version == null) {
            version = 1;
        }
        if (version > 1) {
            if (note.isBlank()) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "变更说明不能为空");
            }
            var previous = versionRow(name, version - 1);
            if (previous != null && sameContent(previous, instructions, tools, examples, cases)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "内容没有变化，不生成新版本");
            }
        }
        jdbc.update("""
                INSERT INTO skill (name, owner_org, description, source)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET owner_org = EXCLUDED.owner_org, description = EXCLUDED.description, source = EXCLUDED.source
                """, name, owner, description, source);
        jdbc.update("""
                INSERT INTO skill_version (skill_name, version, instructions, tools_json, examples_json, cases_json, note, created_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
                """, name, version, instructions, write(tools), write(examples), write(cases), note, author());
        return Map.of("name", name, "version", version);
    }

    @Transactional
    public Map<String, Object> detail(String name) {
        requireSkill(name);
        settle(name);
        return readDetail(name);
    }

    @Transactional
    public Map<String, Object> revoke(String agent, String skill) {
        requireAgent(agent);
        var removed = jdbc.update(
                "DELETE FROM agent_skill_grant WHERE agent_name = ? AND skill_name = ? AND status = 'GRANTED'", agent, skill);
        if (removed == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "没有这项技能授权");
        }
        return Map.of("agent", agent, "skill", skill);
    }

    public void assertCanUpgrade(String agent, String skill) {
        var latest = latestVersion(skill);
        var grant = grantOf(agent, skill);
        if (grant.version() >= latest) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "已经是最新版本");
        }
        if ("RUNNING".equals(grant.state())) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "回归还在进行");
        }
    }

    /** Pins spec.skills to the latest version and records the regression run. The grant version stays put until the run passes. */
    @Transactional
    public Map<String, Object> trackUpgrade(String agent, String skill, String user, String runId) {
        if (user == null || user.isBlank() || runId == null || runId.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "回归运行号不能为空");
        }
        assertCanUpgrade(agent, skill);
        var latest = latestVersion(skill);
        pin(agent, skill, latest);
        jdbc.update("""
                UPDATE agent_skill_grant
                SET upgrade_state = 'RUNNING', upgrade_run_id = ?, upgrade_target = ?,
                    upgrade_score = NULL, upgrade_threshold = NULL, upgrade_error = NULL, granted_by = ?
                WHERE agent_name = ? AND skill_name = ? AND status = 'GRANTED'
                """, runId, latest, user, agent, skill);
        var body = new LinkedHashMap<String, Object>();
        body.put("agent", agent);
        body.put("skill", skill);
        body.put("version", latest);
        body.put("runId", runId);
        body.put("status", "RUNNING");
        return body;
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
                readStrings(rs.getString(4)), readCases(rs.getString(5)), "", "", ""), name, version);
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

    private void settle(String skill) {
        var grants = jdbc.query("""
                SELECT agent_name, version, upgrade_run_id, upgrade_target
                FROM agent_skill_grant
                WHERE skill_name = ? AND status = 'GRANTED' AND upgrade_state = 'RUNNING'
                """, (rs, n) -> new Pending(rs.getString(1), rs.getInt(2), rs.getString(3)), skill);
        for (var grant : grants) {
            var run = runOf(grant.runId());
            if (run == null || "QUEUED".equals(run.state()) || "RUNNING".equals(run.state())) {
                continue;
            }
            if ("DONE".equals(run.state()) && run.passed()) {
                jdbc.update("""
                        UPDATE agent_skill_grant
                        SET version = upgrade_target, upgrade_state = 'PASSED', upgrade_score = ?, upgrade_threshold = ?, upgrade_error = NULL
                        WHERE agent_name = ? AND skill_name = ? AND upgrade_state = 'RUNNING'
                        """, run.score(), run.threshold(), grant.agent(), skill);
                continue;
            }
            pin(grant.agent(), skill, grant.version());
            var error = run.error().isBlank() ? "回归未通过" : run.error();
            jdbc.update("""
                    UPDATE agent_skill_grant
                    SET upgrade_state = 'FAILED', upgrade_score = ?, upgrade_threshold = ?, upgrade_error = ?
                    WHERE agent_name = ? AND skill_name = ? AND upgrade_state = 'RUNNING'
                    """, run.score(), run.threshold(), error, grant.agent(), skill);
        }
    }

    private Run runOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return new Run("FAILED", false, null, 0.85, "回归运行号为空");
        }
        var rows = jdbc.query("SELECT state, result::text, error FROM eval_run WHERE run_id = ?", (rs, n) -> {
            var parsed = score(rs.getString(2));
            var state = rs.getString(1);
            var passed = "DONE".equals(state) && parsed.passed();
            return new Run(state, passed, parsed.score(), parsed.threshold(), rs.getString(3) == null ? "" : rs.getString(3));
        }, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Score score(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Score(false, null, 0.85);
        }
        try {
            var node = json.readTree(raw);
            var threshold = node.path("gate").path("minScore").isNumber() ? node.path("gate").path("minScore").asDouble() : 0.85;
            Double value = node.path("scoreTotal").isNumber() ? node.path("scoreTotal").asDouble() : null;
            var passed = node.path("passed").isBoolean() ? node.path("passed").asBoolean() : value != null && value >= threshold;
            return new Score(passed, value, threshold);
        } catch (Exception e) {
            return new Score(false, null, 0.85);
        }
    }

    private Map<String, Object> readDetail(String name) {
        var meta = jdbc.queryForMap("SELECT owner_org, description, source FROM skill WHERE name = ?", name);
        var versions = skillVersions(name);
        var latest = versions.isEmpty() ? 0 : versions.getLast().version();
        var users = new ArrayList<Map<String, Object>>();
        jdbc.query("""
                SELECT agent_name, version, upgrade_state, upgrade_score, upgrade_threshold, upgrade_error
                FROM agent_skill_grant WHERE skill_name = ? AND status = 'GRANTED' ORDER BY agent_name
                """, rs -> {
            var locked = rs.getInt("version");
            var state = rs.getString("upgrade_state");
            var body = new LinkedHashMap<String, Object>();
            body.put("agent", rs.getString("agent_name"));
            body.put("version", locked);
            body.put("status", "RUNNING".equals(state) ? "RUNNING" : locked >= latest ? "LATEST" : "BEHIND");
            var score = rs.getObject("upgrade_score");
            if (score instanceof Number number) {
                body.put("score", number.doubleValue());
            }
            var threshold = rs.getObject("upgrade_threshold");
            if (threshold instanceof Number number) {
                body.put("threshold", number.doubleValue());
            }
            var error = rs.getString("upgrade_error");
            if (error != null && !error.isBlank()) {
                body.put("upgradeError", error);
            }
            users.add(body);
        }, name);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("ownerOrg", meta.get("owner_org"));
        body.put("description", meta.get("description"));
        body.put("source", meta.get("source"));
        body.put("latest", latest);
        body.put("versions", versions.stream().map(this::versionBody).toList());
        body.put("users", users);
        return body;
    }

    private List<Version> skillVersions(String name) {
        return jdbc.query("""
                SELECT version, instructions, tools_json::text, examples_json::text, cases_json::text, note, created_by, created_at
                FROM skill_version WHERE skill_name = ? ORDER BY version
                """, (rs, n) -> new Version(rs.getInt(1), rs.getString(2), readStrings(rs.getString(3)),
                readStrings(rs.getString(4)), readCases(rs.getString(5)), rs.getString(6), rs.getString(7),
                rs.getTimestamp(8) == null ? "" : rs.getTimestamp(8).toInstant().toString()), name);
    }

    private Map<String, Object> versionBody(Version version) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version.version());
        body.put("instructions", version.instructions());
        body.put("note", version.note() == null ? "" : version.note());
        body.put("createdBy", version.createdBy() == null ? "" : version.createdBy());
        body.put("createdAt", version.createdAt());
        body.put("tools", version.tools());
        body.put("examples", version.examples());
        body.put("cases", version.cases());
        return body;
    }

    private int latestVersion(String name) {
        requireSkill(name);
        var version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM skill_version WHERE skill_name = ?", Integer.class, name);
        if (version == null || version == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "技能版本不存在");
        }
        return version;
    }

    private Locked grantOf(String agent, String skill) {
        requireAgent(agent);
        var rows = jdbc.query("""
                SELECT version, upgrade_state FROM agent_skill_grant
                WHERE agent_name = ? AND skill_name = ? AND status = 'GRANTED'
                """, (rs, n) -> new Locked(rs.getInt(1), rs.getString(2)), agent, skill);
        if (rows.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "没有这项技能授权");
        }
        return rows.getFirst();
    }

    private void pin(String agent, String skill, int version) {
        var rows = jdbc.query("""
                SELECT DISTINCT ON (env) id, manifest_json::text
                FROM agent_version WHERE agent_name = ?
                ORDER BY env, released_at DESC, id DESC
                """, (rs, n) -> Map.<String, Object>of("id", rs.getLong(1), "json", rs.getString(2)), agent);
        for (var row : rows) {
            try {
                var tree = json.readTree(String.valueOf(row.get("json")));
                if (!(tree instanceof ObjectNode root)) {
                    continue;
                }
                var spec = root.with("spec");
                var current = spec.get("skills");
                ArrayNode skills = current instanceof ArrayNode array ? array : spec.putArray("skills");
                var found = false;
                for (var item : skills) {
                    if (item instanceof ObjectNode object && skill.equals(object.path("name").asText())) {
                        object.put("version", version);
                        found = true;
                    }
                }
                if (!found) {
                    skills.addObject().put("name", skill).put("version", version);
                }
                var text = json.writeValueAsString(root);
                jdbc.update("UPDATE agent_version SET manifest_json = ?::jsonb, manifest_hash = ? WHERE id = ?",
                        text, sha256(text), row.get("id"));
            } catch (KeelException e) {
                throw e;
            } catch (Exception e) {
                throw new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "无法改写 spec.skills");
            }
        }
    }

    private void requireSkill(String name) {
        var found = jdbc.queryForObject("SELECT count(*) FROM skill WHERE name = ?", Integer.class, name);
        if (found == null || found == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "技能不存在");
        }
    }

    private String author() {
        var principal = ConsolePrincipal.current();
        if (principal == null) {
            return "";
        }
        if (principal.displayName() != null && !principal.displayName().isBlank()) {
            return principal.displayName();
        }
        return principal.username() == null ? "" : principal.username();
    }

    private boolean sameContent(Version previous, String instructions, List<String> tools, List<String> examples, List<Map<String, String>> cases) {
        return previous.instructions().equals(instructions) && previous.tools().equals(tools)
                && previous.examples().equals(examples) && previous.cases().equals(cases);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private record Version(int version, String instructions, List<String> tools, List<String> examples,
                           List<Map<String, String>> cases, String note, String createdBy, String createdAt) {}

    private record Locked(int version, String state) {}

    private record Pending(String agent, int version, String runId) {}

    private record Run(String state, boolean passed, Double score, double threshold, String error) {}

    private record Score(boolean passed, Double score, double threshold) {}
}
