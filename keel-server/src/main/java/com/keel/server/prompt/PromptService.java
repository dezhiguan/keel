package com.keel.server.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.prompt.PromptCatalog.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PromptService {
    private static final Set<String> ENV_LABELS = Set.of("dev", "test", "staging", "production");
    private static final Duration LIST_TTL = Duration.ofSeconds(30);
    private static final java.util.concurrent.ExecutorService VERSION_READS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore VERSION_READ_LIMIT = new Semaphore(4);

    private final PromptCatalog catalog;
    private final LangfuseClient langfuse;
    private final JdbcTemplate jdbc;
    private final AuditStore audit;
    private final ObjectMapper json = new ObjectMapper();
    private final String langfuseHost;
    private final String projectId;
    private final Map<String, CachedList> listCache = new ConcurrentHashMap<>();
    private final AtomicLong listRevision = new AtomicLong();

    private record CachedList(Map<String, Object> value, Instant at) {}

    public PromptService(PromptCatalog catalog, LangfuseClient langfuse, JdbcTemplate jdbc, AuditStore audit,
                         @Value("${LANGFUSE_HOST:}") String langfuseHost,
                         @Value("${LANGFUSE_PROJECT_ID:}") String projectId) {
        this.catalog = catalog;
        this.langfuse = langfuse;
        this.jdbc = jdbc;
        this.audit = audit;
        this.langfuseHost = langfuseHost == null ? "" : langfuseHost.replaceAll("/$", "");
        this.projectId = projectId == null ? "" : projectId;
    }

    public Map<String, Object> list(String env, String agent) {
        var selected = env == null || env.isBlank() ? "all" : env;
        if (!selected.equals("all") && !Set.of("dev", "test", "staging", "prod").contains(selected)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "env 只能是 all、dev、test、staging、prod");
        }
        var actor = PromptCatalog.Actor.current();
        var key = actor.userId() + ":" + actor.org() + ":" + actor.role() + ":" + selected + ":" + (agent == null ? "" : agent);
        var cached = listCache.get(key);
        if (cached != null && cached.at().plus(LIST_TTL).isAfter(Instant.now())) {
            return cached.value();
        }
        var revision = listRevision.get();
        var result = loadList(selected, agent);
        synchronized (listCache) {
            if (listRevision.get() == revision) {
                listCache.put(key, new CachedList(result, Instant.now()));
            }
        }
        return result;
    }

    private Map<String, Object> loadList(String selected, String agent) {
        requireLangfuse();
        var items = new ArrayList<Map<String, Object>>();
        var seen = new ArrayList<String>();
        for (var item : catalog.declared(agent)) {
            seen.add(item.fullName());
            items.add(summary(item, true, load(item.fullName()), selected));
        }
        for (var row : langfuse.listPrompts(null, null).path("data")) {
            var full = row.path("name").asText("");
            if (seen.contains(full) || !full.contains("/")) {
                continue;
            }
            var owner = full.substring(0, full.indexOf('/'));
            var name = full.substring(full.indexOf('/') + 1);
            if (agent != null && !agent.isBlank() && !agent.equals(owner)) {
                continue;
            }
            if (!catalog.knownAgent(owner)) {
                continue;
            }
            var type = "chat".equals(row.path("type").asText("")) ? "chat" : "text";
            items.add(summary(new Item(owner, name, type, catalog.ownerOrg(owner), ""), false, load(full), selected));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("langfuseUrl", promptsUrl());
        body.put("items", items);
        return body;
    }

    public void invalidateListCache() {
        synchronized (listCache) {
            listRevision.incrementAndGet();
            listCache.clear();
        }
    }

    public Map<String, Object> detail(String agent, String name) {
        requireLangfuse();
        if (catalog.ownerOrg(agent) == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var declared = catalog.declared(agent).stream().anyMatch(item -> item.name().equals(name));
        var versions = load(agent + "/" + name);
        Item item = catalog.declared(agent).stream().filter(it -> it.name().equals(name)).findFirst().orElse(null);
        if (item == null) {
            if (versions.isEmpty()) {
                throw new KeelException(ErrorCode.PROMPT_NOT_DECLARED, "这个提示词没有在智能体的 manifest 里声明");
            }
            item = new Item(agent, name, versions.getLast().type(), catalog.ownerOrg(agent), "");
        }
        return detailOf(item, declared, versions);
    }

    public Map<String, Object> version(String agent, String name, int version) {
        var item = resolve(agent, name);
        var remote = one(item.fullName(), version);
        var body = new LinkedHashMap<String, Object>();
        body.put("version", remote.version());
        body.put("type", remote.type());
        body.put("prompt", json.convertValue(remote.prompt(), Object.class));
        body.put("config", configMap(remote.config()));
        body.put("variables", PromptTexts.variables(remote.prompt(), remote.type()));
        return body;
    }

    public Map<String, Object> diff(String agent, String name, int from, int to) {
        var item = resolve(agent, name);
        var left = one(item.fullName(), from);
        var right = one(item.fullName(), to);
        return PromptTexts.diff(left.prompt(), left.type(), right.prompt(), right.type());
    }

    public Map<String, Object> save(String agent, String name, JsonNode body) {
        catalog.authorize(agent);
        var item = catalog.require(agent, name);
        requireLangfuse();
        var message = body.path("commitMessage").asText("").trim();
        if (message.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "保存必须带提交说明");
        }
        var prompt = PromptTexts.asPrompt(body.get("prompt"), item.type());
        var config = PromptTexts.config(body.get("config"));
        var existing = load(item.fullName());
        var created = langfuse.createPrompt(item.fullName(), item.type(), prompt, config, message, List.of());
        var version = created.path("version").asInt();
        var sha = PromptTexts.sha256(prompt, item.type());
        var diffLines = 0;
        if (!existing.isEmpty()) {
            var previous = existing.getLast();
            var diff = PromptTexts.diff(previous.prompt(), previous.type(), prompt, item.type());
            diffLines = number(diff.get("added")) + number(diff.get("removed"));
        }
        writeAudit(agent, "staging", "low", item, payload(version, sha, diffLines, null, text(body, "gitSha"), null));
        invalidateListCache();
        return Map.of("version", version, "sha256", sha);
    }

    public Map<String, Object> promote(String agent, String name, JsonNode body) {
        catalog.authorize(agent);
        var item = catalog.require(agent, name);
        requireLangfuse();
        var env = body.path("env").asText("");
        if ("prod".equals(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "production 标签只经 keel release 或回滚挪动");
        }
        if (!Set.of("dev", "test", "staging").contains(env)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "env 只能是 dev、test 或 staging");
        }
        if (!body.path("version").canConvertToInt()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "version 必须是整数");
        }
        var version = body.path("version").asInt();
        var remote = one(item.fullName(), version);
        if (!item.type().equals(remote.type())) {
            throw new KeelException(ErrorCode.PROMPT_TYPE_MISMATCH, ErrorCode.PROMPT_TYPE_MISMATCH.message());
        }
        langfuse.moveLabel(item.fullName(), version, "prod".equals(env) ? "production" : env);
        if ("staging".equals(env)) {
            jdbc.update("""
                    INSERT INTO prompt_promotion (agent_name, prompt_name, version, sha256, promoted_by, gate_status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """, agent, name, version, remote.sha(), PromptCatalog.Actor.current().userId());
        }
        writeAudit(agent, env, "staging".equals(env) ? "mid" : "low", item, payload(version, remote.sha(), null, null, null, null));
        invalidateListCache();
        var result = new LinkedHashMap<String, Object>();
        result.put("env", env);
        result.put("version", version);
        result.put("gate", "staging".equals(env) ? "pending" : "none");
        return result;
    }

    public Map<String, Object> sync(String agent, JsonNode body) {
        catalog.authorize(agent);
        requireLangfuse();
        invalidateListCache();
        var gitSha = body.path("gitSha").asText("").trim();
        if (gitSha.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "gitSha 不能为空");
        }
        var created = new ArrayList<Map<String, Object>>();
        for (var file : body.path("files")) {
            var name = file.path("name").asText("");
            var item = catalog.require(agent, name);
            var type = file.path("type").asText("");
            if (!item.type().equals(type)) {
                throw new KeelException(ErrorCode.PROMPT_TYPE_MISMATCH, ErrorCode.PROMPT_TYPE_MISMATCH.message());
            }
            var prompt = PromptTexts.asPrompt(file.get("prompt"), type);
            if (!PromptTexts.sameHash(file.path("sha256").asText(""), prompt, type)) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "sha256 和提示词内容不一致");
            }
            var sha = PromptTexts.sha256(prompt, type);
            var existing = load(item.fullName());
            if (existing.stream().anyMatch(version -> sha.equals(version.sha()))) {
                continue;
            }
            var message = "from git " + gitSha;
            JsonNode stored = existing.isEmpty()
                    ? langfuse.createPrompt(item.fullName(), type, prompt, json.createObjectNode(), message, List.of("dev", "test", "staging"))
                    : langfuse.createPrompt(item.fullName(), type, prompt, json.createObjectNode(), message, List.of());
            var version = stored.path("version").asInt();
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO prompt_promotion (agent_name, prompt_name, version, sha256, promoted_by, gate_status)
                        VALUES (?, ?, ?, ?, 'ci', 'PENDING')
                        """, agent, name, version, sha);
            }
            writeAudit(agent, "staging", "low", item, payload(version, sha, null, null, gitSha, null));
            created.add(Map.of("name", name, "version", version));
        }
        invalidateListCache();
        return Map.of("created", created);
    }

    public Map<String, Object> recordGate(JsonNode body) {
        var agent = body.path("agent").asText("");
        var gateRunId = body.path("gateRunId").asText("");
        if (agent.isBlank() || gateRunId.isBlank() || !body.has("passed")) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "agent、gateRunId、passed 都要有");
        }
        var pending = jdbc.query("""
                SELECT DISTINCT ON (prompt_name) id, prompt_name, version, gate_status
                FROM prompt_promotion WHERE agent_name = ?
                ORDER BY prompt_name, promoted_at DESC, id DESC
                """, (rs, n) -> new Object[] {rs.getLong("id"), rs.getString("prompt_name"), rs.getInt("version"), rs.getString("gate_status")}, agent);
        var open = pending.stream().filter(row -> "PENDING".equals(row[3])).toList();
        if (open.isEmpty()) {
            return Map.of("updated", 0);
        }
        var reported = body.get("promptVersions");
        var mismatch = false;
        if (reported != null && !reported.isNull()) {
            mismatch = !reported.isObject();
            if (!mismatch) {
                for (var row : open) {
                    var seen = reported.get(String.valueOf(row[1]));
                    if (seen == null || seen.isNull() || !seen.canConvertToInt() || seen.asInt() != (Integer) row[2]) {
                        mismatch = true;
                        break;
                    }
                }
            }
        }
        var status = mismatch ? "INVALID" : body.path("passed").asBoolean() ? "PASSED" : "FAILED";
        for (var row : open) {
            jdbc.update("UPDATE prompt_promotion SET gate_status = ?, gate_run_id = ? WHERE id = ?", status, gateRunId, row[0]);
        }
        invalidateListCache();
        return Map.of("updated", open.size(), "gate", status.toLowerCase());
    }

    private Map<String, Object> summary(Item item, boolean declared, List<Remote> versions, String selectedEnv) {
        var labs = labs(versions);
        var gate = gate(item.agent(), item.name());
        var drift = driftEnvs(item, labs);
        var code = codeVersion(versions, labs);
        var latest = versions.isEmpty() ? null : versions.getLast();
        var staging = versions.stream().filter(version -> version.labels().contains("staging")).reduce((a, b) -> b).orElse(latest);
        var body = new LinkedHashMap<String, Object>();
        body.put("agent", item.agent());
        body.put("name", item.name());
        body.put("type", item.type());
        body.put("declared", declared);
        body.put("summary", staging == null ? null : staging.commitMessage());
        body.put("updatedAt", latest == null ? null : latest.createdAt());
        body.put("updatedBy", latest == null ? null : latest.createdBy());
        body.put("gate", gate.status());
        body.put("gateNote", gate.note());
        body.put("drift", drift);
        body.put("codeVersion", code == null ? null : Map.of("version", code.version(), "gitSha", gitSha(code)));
        var envs = new LinkedHashMap<String, Object>();
        for (var env : List.of("dev", "test", "staging", "prod")) {
            if (!selectedEnv.equals("all") && !selectedEnv.equals(env)) {
                continue;
            }
            var version = labs.get(env);
            if (version == null) {
                continue;
            }
            var cell = new LinkedHashMap<String, Object>();
            cell.put("version", version);
            if ("staging".equals(env)) {
                cell.put("gate", gate.status());
            }
            envs.put(env, cell);
        }
        body.put("envs", envs);
        body.put("states", states(declared, gate.status(), drift, code != null, labs));
        return body;
    }

    private Map<String, Object> detailOf(Item item, boolean declared, List<Remote> versions) {
        var labs = labs(versions);
        var gate = gate(item.agent(), item.name());
        var drift = driftDetails(item, labs);
        var code = codeVersion(versions, labs);
        var latest = versions.isEmpty() ? null : versions.getLast();
        var body = new LinkedHashMap<String, Object>();
        body.put("agent", item.agent());
        body.put("name", item.name());
        body.put("type", item.type());
        body.put("declared", declared);
        body.put("config", latest == null ? Map.of() : configMap(latest.config()));
        body.put("gate", gate.status());
        body.put("gateNote", gate.note());
        body.put("langfuseUrl", promptsUrl());
        body.put("metricsUrl", metricsUrl(item.fullName()));
        body.put("drift", drift);
        if (code == null) {
            body.put("codeVersion", null);
        } else {
            body.put("codeVersion", Map.of("version", code.version(), "gitSha", gitSha(code),
                    "commitMessage", code.commitMessage() == null ? "" : code.commitMessage(),
                    "createdBy", code.createdBy() == null ? "" : code.createdBy()));
        }
        body.put("labs", labs);
        body.put("states", states(declared, gate.status(), drift.stream().map(row -> String.valueOf(row.get("env"))).toList(), code != null, labs));
        body.put("releases", releases(item.agent(), item.name()));
        var rows = new ArrayList<Map<String, Object>>();
        for (var version : versions) {
            var row = new LinkedHashMap<String, Object>();
            row.put("version", version.version());
            row.put("labels", version.labels());
            row.put("commitMessage", version.commitMessage());
            row.put("source", version.commitMessage() != null && version.commitMessage().startsWith("from git ") ? "code" : "console");
            row.put("createdAt", version.createdAt());
            row.put("createdBy", version.createdBy());
            row.put("sha256", version.sha());
            rows.add(row);
        }
        body.put("versions", rows);
        return body;
    }

    private List<Map<String, String>> states(boolean declared, String gate, List<String> drift, boolean code, Map<String, Integer> labs) {
        var states = new ArrayList<Map<String, String>>();
        if (!declared) {
            states.add(state("undeclared", "已不在 manifest 中", "p-mute"));
        }
        if (!drift.isEmpty()) {
            states.add(state("drift", "漂移", "p-bad"));
        }
        if ("failed".equals(gate)) {
            states.add(state("failed", "回归未过", "p-bad"));
        }
        if ("invalid".equals(gate)) {
            states.add(state("invalid", "回归作废", "p-bad"));
        }
        if ("pending".equals(gate)) {
            states.add(state("pending", "回归中", "p-warn"));
        }
        if (code) {
            states.add(state("code", "代码里有新版本", "p-warn"));
        }
        int staging = labs.getOrDefault("staging", 0);
        if (labs.getOrDefault("dev", 0) > staging || labs.getOrDefault("test", 0) > staging) {
            states.add(state("trying", "dev / test 在试新版本", "p-soft"));
        }
        var prod = labs.get("prod");
        if (prod == null) {
            states.add(state("unreleased", "未发布", "p-soft"));
        } else if ("passed".equals(gate) && !prod.equals(labs.get("staging"))) {
            states.add(state("ready", "待发布", "p-acc"));
        }
        var blocking = Set.of("undeclared", "drift", "failed", "invalid", "pending", "unreleased", "ready");
        if (states.stream().noneMatch(row -> blocking.contains(row.get("id")))) {
            states.add(state("same", "staging 与 prod 一致", "p-ok"));
        }
        return states;
    }

    private Map<String, String> state(String id, String label, String tone) {
        var row = new LinkedHashMap<String, String>();
        row.put("id", id);
        row.put("label", label);
        row.put("tone", tone);
        return row;
    }

    private List<String> driftEnvs(Item item, Map<String, Integer> labs) {
        return driftDetails(item, labs).stream().map(row -> String.valueOf(row.get("env"))).toList();
    }

    private List<Map<String, Object>> driftDetails(Item item, Map<String, Integer> labs) {
        var rows = new ArrayList<Map<String, Object>>();
        addDrift(rows, "prod", "production", labs.get("prod"), releaseVersion(item.agent(), item.name()));
        addDrift(rows, "staging", "staging", labs.get("staging"), promotionVersion(item.agent(), item.name()));
        return rows;
    }

    private void addDrift(List<Map<String, Object>> rows, String env, String label, Integer actual, Integer expected) {
        if ((expected == null && actual == null) || (expected != null && expected.equals(actual))) {
            return;
        }
        var text = new StringBuilder("有人绕过 Keel 改了 ").append(label).append(" 标签");
        text.append(actual == null ? "，标签已经被拿掉" : "，现在在 v" + actual);
        if (expected != null) {
            text.append("，Keel 记录的是 v").append(expected);
        }
        text.append("。Keel 不会自动改回，已告警并写审计。");
        rows.add(Map.of("env", env, "text", text.toString()));
    }

    private Gate gate(String agent, String name) {
        var rows = jdbc.query("""
                SELECT gate_status, gate_run_id FROM prompt_promotion
                WHERE agent_name = ? AND prompt_name = ?
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """, (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, agent, name);
        if (rows.isEmpty()) {
            return new Gate("none", null);
        }
        var status = rows.getFirst()[0].toLowerCase();
        var run = rows.getFirst()[1];
        var note = switch (status) {
            case "pending" -> "请手动运行 keel gate";
            case "failed" -> run == null ? "回归未过" : "回归未过（" + run + "）";
            case "invalid" -> "实验里的提示词版本和 staging 当前版本不一致，这次回归作废";
            default -> null;
        };
        return new Gate(status, note);
    }

    private Integer promotionVersion(String agent, String name) {
        var rows = jdbc.query("""
                SELECT version FROM prompt_promotion WHERE agent_name = ? AND prompt_name = ?
                ORDER BY promoted_at DESC, id DESC LIMIT 1
                """, (rs, n) -> rs.getInt(1), agent, name);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Integer releaseVersion(String agent, String name) {
        for (var raw : releaseJson(agent)) {
            var version = raw.path(name).path("version");
            if (version.canConvertToInt()) {
                return version.asInt();
            }
        }
        return null;
    }

    private List<Map<String, Object>> releases(String agent, String name) {
        var rows = jdbc.query("""
                SELECT prompt_versions_json::text AS body, created_at, kind, ci_run_url
                FROM release_record
                WHERE agent_name = ? AND env = 'prod' AND prompt_versions_json IS NOT NULL
                ORDER BY created_at, id
                """, (rs, n) -> new Object[] {rs.getString("body"), rs.getTimestamp("created_at").toInstant().toString(), rs.getString("kind"), rs.getString("ci_run_url")}, agent);
        var releases = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            try {
                var version = json.readTree(String.valueOf(row[0])).path(name).path("version");
                if (!version.canConvertToInt()) {
                    continue;
                }
                var item = new LinkedHashMap<String, Object>();
                item.put("version", version.asInt());
                item.put("at", row[1]);
                item.put("gate", "rollback".equals(row[2]) ? "回滚" : row[3]);
                releases.add(item);
            } catch (Exception ignored) {
                // A broken release row does not hide the versions that can still be read.
            }
        }
        return releases;
    }

    private List<JsonNode> releaseJson(String agent) {
        var rows = jdbc.query("""
                SELECT prompt_versions_json::text FROM release_record
                WHERE agent_name = ? AND env = 'prod' AND prompt_versions_json IS NOT NULL
                ORDER BY created_at DESC, id DESC
                """, (rs, n) -> rs.getString(1), agent);
        var nodes = new ArrayList<JsonNode>();
        for (var raw : rows) {
            try {
                nodes.add(json.readTree(raw));
            } catch (Exception ignored) {
                nodes.add(json.createObjectNode());
            }
        }
        return nodes;
    }

    private Item resolve(String agent, String name) {
        requireLangfuse();
        var declared = catalog.declared(agent).stream().filter(item -> item.name().equals(name)).findFirst();
        if (declared.isPresent()) {
            return declared.get();
        }
        if (catalog.ownerOrg(agent) == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
        var versions = load(agent + "/" + name);
        if (versions.isEmpty()) {
            throw new KeelException(ErrorCode.PROMPT_NOT_DECLARED, ErrorCode.PROMPT_NOT_DECLARED.message());
        }
        return new Item(agent, name, versions.getLast().type(), catalog.ownerOrg(agent), "");
    }

    private List<Remote> load(String fullName) {
        var numbers = new ArrayList<Integer>();
        langfuse.listPrompts(fullName, null).path("data").forEach(row -> {
            if (!fullName.equals(row.path("name").asText())) {
                return;
            }
            row.path("versions").forEach(number -> {
                if (number.canConvertToInt()) {
                    numbers.add(number.asInt());
                }
            });
        });
        var reads = numbers.stream().map(number -> CompletableFuture.supplyAsync(() -> {
            VERSION_READ_LIMIT.acquireUninterruptibly();
            try {
                var body = langfuse.getPrompt(fullName, number, null);
                return body == null ? null : remote(body);
            } finally {
                VERSION_READ_LIMIT.release();
            }
        }, VERSION_READS)).toList();
        var versions = new ArrayList<Remote>();
        for (var read : reads) {
            var version = read.join();
            if (version != null) {
                versions.add(version);
            }
        }
        versions.sort((a, b) -> Integer.compare(a.version(), b.version()));
        return versions;
    }

    private Remote one(String fullName, int version) {
        var body = langfuse.getPrompt(fullName, version, null);
        if (body == null) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, "没有这个提示词版本");
        }
        return remote(body);
    }

    private Remote remote(JsonNode body) {
        var labels = new ArrayList<String>();
        body.path("labels").forEach(label -> {
            var text = label.asText("");
            if (!text.isBlank()) {
                labels.add(text);
            }
        });
        var type = "chat".equals(body.path("type").asText("text")) ? "chat" : "text";
        var prompt = body.get("prompt");
        return new Remote(body.path("version").asInt(), type, prompt, body.path("config"), labels,
                text(body, "commitMessage"), text(body, "createdAt"), firstText(body, "createdBy"), PromptTexts.sha256(prompt, type));
    }

    private Remote codeVersion(List<Remote> versions, Map<String, Integer> labs) {
        Remote code = null;
        for (var version : versions) {
            if (version.commitMessage() != null && version.commitMessage().startsWith("from git ") && !labs.containsValue(version.version())) {
                code = version;
            }
        }
        return code;
    }

    private Map<String, Integer> labs(List<Remote> versions) {
        var labs = new LinkedHashMap<String, Integer>();
        for (var version : versions) {
            for (var label : version.labels()) {
                if (ENV_LABELS.contains(label)) {
                    labs.put("production".equals(label) ? "prod" : label, version.version());
                }
            }
        }
        return labs;
    }

    private Map<String, Object> configMap(JsonNode config) {
        var copy = new LinkedHashMap<String, Object>();
        if (config != null && config.isObject()) {
            config.fields().forEachRemaining(entry -> {
                if (Set.of("temperature", "max_tokens", "top_p").contains(entry.getKey())) {
                    copy.put(entry.getKey(), entry.getValue().isNumber() ? entry.getValue().numberValue() : entry.getValue().asText());
                }
            });
        }
        return copy;
    }

    private void writeAudit(String agent, String env, String risk, Item item, Map<String, Object> payload) {
        audit.append(agent, env, "config.change", risk, "allowed", "prompt:" + item.fullName(), null,
                PromptCatalog.Actor.current().userId(), payload);
    }

    private Map<String, Object> payload(Integer version, String sha, Integer diffLines, String gateRunId, String gitSha, String kind) {
        var payload = new LinkedHashMap<String, Object>();
        if (version != null) {
            payload.put("version", version);
        }
        if (sha != null) {
            payload.put("sha256", sha);
        }
        if (diffLines != null) {
            payload.put("diffLines", diffLines);
        }
        if (gateRunId != null && !gateRunId.isBlank()) {
            payload.put("gateRunId", gateRunId);
        }
        if (gitSha != null && !gitSha.isBlank()) {
            payload.put("gitSha", gitSha);
        }
        if (kind != null) {
            payload.put("kind", kind);
        }
        return payload;
    }

    private void requireLangfuse() {
        if (!langfuse.ready()) {
            throw new KeelException(ErrorCode.PROMPT_UNAVAILABLE, "Langfuse 未配置");
        }
    }

    private String promptsUrl() {
        if (langfuseHost.isBlank() || projectId.isBlank()) {
            return "";
        }
        return langfuseHost + "/project/" + projectId + "/prompts";
    }

    private String metricsUrl(String fullName) {
        var base = promptsUrl();
        return base.isBlank() ? "" : base + "/" + URLEncoder.encode(fullName, StandardCharsets.UTF_8);
    }

    private static String gitSha(Remote version) {
        var message = version.commitMessage();
        return message != null && message.startsWith("from git ") ? message.substring("from git ".length()) : "";
    }

    private static String text(JsonNode body, String field) {
        var value = body.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static String firstText(JsonNode body, String... fields) {
        for (var field : fields) {
            var value = text(body, field);
            if (value != null) {
                return value;
            }
        }
        return "langfuse";
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record Remote(int version, String type, JsonNode prompt, JsonNode config, List<String> labels,
                          String commitMessage, String createdAt, String createdBy, String sha) {}

    private record Gate(String status, String note) {}
}
