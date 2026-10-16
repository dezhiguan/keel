package com.keel.server.registry.service;

import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.model.dto.AgentElements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Reads records that already exist. It does not start a run or invent a pass. */
@Service
public class ElementEvidence {
    private final JdbcTemplate jdbc;

    public ElementEvidence(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AgentDetail apply(AgentDetail detail) {
        if (detail == null || detail.elements() == null || detail.summary() == null || detail.summary().name() == null) {
            return detail;
        }
        var agent = detail.summary().name();
        var next = detail.elements().withRuntime(
                count("SELECT count(*) FROM console_audit_event WHERE agent=? AND action='tool.call' AND decision='allowed'", agent) > 0,
                false,
                count("SELECT count(*) FROM user_memory WHERE agent_name=?", agent) > 0,
                count("SELECT count(*) FROM eval_run WHERE agent_name=? AND state='DONE' AND result->>'passed'='true'", agent) > 0,
                count("SELECT count(*) FROM console_audit_event WHERE agent=?", agent) > 0);
        return detail.withElements(next);
    }

    private int count(String sql, String agent) {
        Integer value = jdbc.queryForObject(sql, Integer.class, agent);
        return value == null ? 0 : value;
    }
}
