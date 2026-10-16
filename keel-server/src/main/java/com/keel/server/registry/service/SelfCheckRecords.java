package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.model.dto.AgentDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Map;

@Component
public class SelfCheckRecords {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SelfCheckRecords(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void save(String agent, String env, com.fasterxml.jackson.databind.JsonNode manifest, SelfCheckService.Report report) {
        try {
            // jsonb changes object key order. Match content and retain the registered version hash.
            var written = jdbc.update("""
                    INSERT INTO agent_self_check(agent_name,env,manifest_hash,report)
                    SELECT agent_name,env,manifest_hash,?::jsonb FROM agent_version
                    WHERE agent_name=? AND env=? AND manifest_json=?::jsonb
                    ORDER BY released_at DESC LIMIT 1
                    ON CONFLICT(agent_name,env) DO UPDATE SET manifest_hash=EXCLUDED.manifest_hash,
                    report=EXCLUDED.report, checked_at=now()
                    """, json.writeValueAsString(report), agent, env, manifest.toString());
            if (written != 1) throw new IllegalStateException("自检期间登记配置已变更，请重新执行");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("自检报告保存失败", e);
        }
    }

    public AgentDetail apply(AgentDetail detail) {
        var rows = jdbc.query("SELECT report::text, checked_at FROM agent_self_check WHERE agent_name=? AND env=? AND manifest_hash=?",
                (rs,n) -> {
                    com.fasterxml.jackson.databind.JsonNode node;
                    try { node = json.readTree(rs.getString(1)); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new java.sql.SQLException("自检报告无法解析",e); }
                    var names = Map.of("健康", "/v1/health 返回 200", "协议", "调用协议自检", "追踪", "追踪上报正常", "审批绑定", "高风险工具已绑定审批");
                    var actual = new java.util.LinkedHashMap<String, AgentDetail.CheckItem>();
                    for (var item : node.path("items")) {
                        var name = names.getOrDefault(item.path("name").asText(), item.path("name").asText());
                        actual.put(name, new AgentDetail.CheckItem(name, item.path("passed").asBoolean(), item.path("detail").isNull() ? null : item.path("detail").asText()));
                    }
                    var items = new ArrayList<AgentDetail.CheckItem>();
                    for (var item : detail.selfCheck().items()) items.add(actual.getOrDefault(item.name(), item));
                    if (items.isEmpty()) items.addAll(actual.values());
                    return new AgentDetail.CheckReport(node.path("passed").asBoolean(), items, rs.getTimestamp(2).toInstant().toString());
                }, detail.summary().name(), detail.summary().env(), detail.manifestHash());
        return rows.isEmpty() ? detail : detail.withSelfCheck(rows.getFirst());
    }
}
