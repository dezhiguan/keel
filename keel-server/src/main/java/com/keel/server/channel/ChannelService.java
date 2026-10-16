package com.keel.server.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.authgw.ChannelJwtVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class ChannelService {
    private static final Set<String> KINDS = Set.of("HOSTED", "EMBED", "WEB", "MINIPROGRAM");
    private static final Set<String> IDENTITIES = Set.of("EXCHANGE", "OPENID", "ANONYMOUS");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ChannelJwtVerifier identities;
    private final SecureRandom random = new SecureRandom();

    public ChannelService(JdbcTemplate jdbc, ObjectMapper json, ChannelJwtVerifier identities) {
        this.jdbc = jdbc;
        this.json = json;
        this.identities = identities;
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

    @Transactional
    public Map<String, Object> publish(String agent, JsonNode body) {
        requireAgent(agent);
        var kind = body.path("kind").asText("").trim();
        var identity = body.path("identity").asText("").trim();
        if (!KINDS.contains(kind) || !IDENTITIES.contains(identity)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道方式或身份无效");
        }
        var origin = body.path("origin").asText("").trim();
        if (!"HOSTED".equals(kind) && origin.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "网页渠道要登记域名，小程序渠道要登记 appid");
        }
        if ("MINIPROGRAM".equals(kind) && (!"EXCHANGE".equals(identity)
                || !origin.matches("[a-zA-Z0-9_-]{3,80}"))) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "小程序需使用换票身份，并登记换票客户端 ID");
        }
        if ("ANONYMOUS".equals(identity) && highRisk(agent)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "匿名访客不能发布到有高风险工具的智能体");
        }
        var quota = body.path("quotaPerHour").isNumber() ? body.path("quotaPerHour").asInt() : 30;
        if (quota < 1) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "渠道配额必须大于零");
        // Authenticated channels require a verified identity exchange; a public
        // credential alone must never make them callable.
        var status = "ANONYMOUS".equals(identity) || ("EXCHANGE".equals(identity) && !"HOSTED".equals(kind))
                ? "ONLINE" : "REGISTERED";
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

    public Map<String, Object> entry(String agent, JsonNode body, String requestOrigin, String authorization) {
        requireAgent(agent);
        var credential = body.path("credential").asText("");
        var allowed = jdbc.query("""
                SELECT c.id, c.origin, c.kind, c.identity, c.quota_per_hour FROM channel c
                JOIN channel_credential d ON d.channel_id = c.id
                WHERE c.agent_name = ? AND d.public_id = ? AND c.status = 'ONLINE'
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getString("kind"), rs.getString("identity"),
                        rs.getString("origin"), (Integer) rs.getObject("quota_per_hour")), agent, credential);
        if (allowed.isEmpty()) {
            throw new KeelException(ErrorCode.CHANNEL_ORIGIN_DENIED, ErrorCode.CHANNEL_ORIGIN_DENIED.message());
        }
        var row = allowed.getFirst();
        if ("OPENID".equals(row.identity())) throw denied();
        if ("ANONYMOUS".equals(row.identity()) && highRisk(agent)) throw denied();
        if ("HOSTED".equals(row.kind())) ChannelOrigin.require(requestOrigin, List.of("https://keel.ragforge.net"));
        else ChannelOrigin.require(requestOrigin, List.of(row.origin() == null ? "" : row.origin()));
        if ("EXCHANGE".equals(row.identity())) identities.user(authorization,
                "MINIPROGRAM".equals(row.kind()) ? row.origin() : null);
        return Map.of(
                "invokeUrl", "/a/api/" + agent + "/invoke",
                "feedbackUrl", "/a/api/" + agent + "/feedback");
    }

    public String hostedCredential(String agent) {
        var rows = jdbc.query("""
                SELECT d.public_id FROM channel c JOIN channel_credential d ON d.channel_id=c.id
                WHERE c.agent_name=? AND c.kind='HOSTED' AND c.status='ONLINE' ORDER BY c.id DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), agent);
        if (rows.isEmpty()) throw denied();
        return rows.getFirst();
    }

    public String admit(String agent, String credential, String requestOrigin, String authorization) {
        var body = json.createObjectNode().put("credential", credential);
        entry(agent, body, requestOrigin, authorization);
        var rows = jdbc.query("""
                SELECT c.id, COALESCE(c.quota_per_hour,30) AS quota, c.identity, c.kind, c.origin FROM channel c
                JOIN channel_credential d ON d.channel_id=c.id
                WHERE c.agent_name=? AND d.public_id=? AND c.status='ONLINE'
                """, (rs, n) -> new Entry(rs.getLong(1), rs.getString(4), rs.getString(3),
                        rs.getString(5), rs.getInt(2)), agent, credential);
        if (rows.isEmpty()) throw denied();
        var hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        var accepted = jdbc.query("""
                INSERT INTO channel_usage(channel_id,window_start,hits) VALUES (?,?,1)
                ON CONFLICT (channel_id,window_start) DO UPDATE SET hits=channel_usage.hits+1
                WHERE channel_usage.hits < ? RETURNING hits
                """, (rs, n) -> rs.getInt(1), rows.getFirst().id(), java.sql.Timestamp.from(hour), rows.getFirst().quota());
        if (accepted.isEmpty()) throw new KeelException(ErrorCode.GW_QUOTA_EXCEEDED, "渠道每小时配额已用尽");
        return "EXCHANGE".equals(rows.getFirst().identity())
                ? identities.user(authorization, "MINIPROGRAM".equals(rows.getFirst().kind())
                    ? rows.getFirst().origin() : null) : "";
    }

    public void feedback(String agent, String credential, String requestOrigin, String authorization, String traceId, int value) {
        entry(agent, json.createObjectNode().put("credential", credential), requestOrigin, authorization);
        if (traceId == null || !traceId.matches("[a-fA-F0-9]{32}") || (value != -1 && value != 1)) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "反馈缺少有效链路或分值");
        }
        jdbc.update("""
                INSERT INTO channel_feedback(channel_id,agent_name,trace_id,value)
                SELECT c.id,?,?,? FROM channel c JOIN channel_credential d ON d.channel_id=c.id
                WHERE c.agent_name=? AND d.public_id=? AND c.status='ONLINE'
                ON CONFLICT (channel_id,trace_id) DO UPDATE SET value=EXCLUDED.value, created_at=now()
                """, agent, traceId, value, agent, credential);
    }

    private static KeelException denied() {
        return new KeelException(ErrorCode.CHANNEL_ORIGIN_DENIED, ErrorCode.CHANNEL_ORIGIN_DENIED.message());
    }

    private record Entry(long id, String kind, String identity, String origin, Integer quota) {}

    private boolean highRisk(String agent) {
        var rows = jdbc.query("SELECT manifest_json::text FROM agent_version WHERE agent_name = ?",
                (rs, n) -> rs.getString(1), agent);
        if (rows.isEmpty()) return true;
        for (var raw : rows) {
            try {
                var tools = json.readTree(raw).path("spec").path("tools");
                for (var tool : tools) {
                    if ("high".equals(tool.path("risk").asText(""))) return true;
                }
            } catch (Exception ignored) {
                // A broken manifest cannot prove that anonymous access is safe.
                return true;
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
        return "<script src=\"https://keel.ragforge.net/a/widget.js\" data-agent=\"" + agent + "\" data-credential=\"" + credential + "\"></script>";
    }
}
