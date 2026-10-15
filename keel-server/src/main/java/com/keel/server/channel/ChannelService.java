package com.keel.server.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChannelService {
    private static final Set<String> KINDS = Set.of("HOSTED", "EMBED", "WEB");
    private static final Set<String> IDENTITIES = Set.of("EXCHANGE", "OPENID", "ANONYMOUS");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ChannelService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Map<String, Object> list(String agent) {
        requireAgent(agent);
        var items = jdbc.query("""
                SELECT c.id, c.kind, c.identity, c.origin, c.quota_per_hour, c.status, d.public_id
                FROM channel c JOIN channel_credential d ON d.channel_id = c.id
                WHERE c.agent_name = ? ORDER BY c.id
                """, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", rs.getLong("id"));
            row.put("kind", rs.getString("kind"));
            row.put("identity", rs.getString("identity"));
            row.put("origin", rs.getString("origin"));
            row.put("quotaPerHour", rs.getObject("quota_per_hour"));
            row.put("status", rs.getString("status"));
            row.put("credential", rs.getString("public_id"));
            row.put("hostedPath", "/a/" + agent);
            row.put("embed", embed(agent, rs.getString("public_id")));
            return row;
        }, agent);
        return Map.of("items", items);
    }

    public Map<String, Object> publish(String agent, JsonNode body) {
        requireAgent(agent);
        var kind = body.path("kind").asText("").trim();
        var identity = body.path("identity").asText("").trim();
        if (!KINDS.contains(kind) || !IDENTITIES.contains(identity)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道方式或身份无效");
        }
        var origin = body.path("origin").asText("").trim();
        if (!"HOSTED".equals(kind) && origin.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "嵌入和网页渠道要登记域名");
        }
        if ("ANONYMOUS".equals(identity) && highRisk(agent)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "匿名访客不能发布到有高风险工具的智能体");
        }
        var quota = body.path("quotaPerHour").isNumber() ? body.path("quotaPerHour").asInt() : null;
        var status = "OPENID".equals(identity) ? "REGISTERED" : "ONLINE";
        var id = jdbc.queryForObject("""
                INSERT INTO channel (agent_name, kind, identity, origin, quota_per_hour, status)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, agent, kind, identity, origin.isBlank() ? null : origin, quota, status);
        var credential = credential();
        jdbc.update("INSERT INTO channel_credential (channel_id, public_id) VALUES (?, ?)", id, credential);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("credential", credential);
        result.put("hostedPath", "/a/" + agent);
        result.put("embed", embed(agent, credential));
        result.put("status", status);
        return result;
    }

    public Map<String, Object> entry(String agent, JsonNode body) {
        requireAgent(agent);
        var origin = body.path("origin").asText("");
        var credential = body.path("credential").asText("");
        var allowed = jdbc.query("""
                SELECT c.origin, c.kind FROM channel c
                JOIN channel_credential d ON d.channel_id = c.id
                WHERE c.agent_name = ? AND d.public_id = ? AND c.status = 'ONLINE'
                """, (rs, n) -> new String[]{rs.getString("kind"), rs.getString("origin")}, agent, credential);
        if (allowed.isEmpty()) {
            throw new KeelException(ErrorCode.CHANNEL_ORIGIN_DENIED, ErrorCode.CHANNEL_ORIGIN_DENIED.message());
        }
        var kind = allowed.getFirst()[0];
        if (!"HOSTED".equals(kind)) {
            ChannelOrigin.require(origin, List.of(allowed.getFirst()[1] == null ? "" : allowed.getFirst()[1]));
        }
        return Map.of(
                "invokeUrl", "/agents/" + agent + "/v1/invoke",
                "feedbackUrl", "/agents/" + agent + "/v1/feedback");
    }

    private boolean highRisk(String agent) {
        var rows = jdbc.query("SELECT manifest_json::text FROM agent_version WHERE agent_name = ?",
                (rs, n) -> rs.getString(1), agent);
        for (var raw : rows) {
            try {
                var tools = json.readTree(raw).path("spec").path("tools");
                for (var tool : tools) {
                    if ("high".equals(tool.path("risk").asText(""))) return true;
                }
            } catch (Exception ignored) {
                // A broken manifest does not by itself allow anonymous access.
            }
        }
        return false;
    }

    private void requireAgent(String agent) {
        var found = jdbc.queryForObject("SELECT count(*) FROM agent WHERE name = ?", Integer.class, agent);
        if (found == null || found == 0) {
            throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
        }
    }

    private String credential() {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        return "ch_" + HexFormat.of().formatHex(bytes);
    }

    private static String embed(String agent, String credential) {
        return "<script src=\"/a/widget.js\" data-agent=\"" + agent + "\" data-credential=\"" + credential + "\"></script>";
    }
}
