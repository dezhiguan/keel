package com.keel.server.builtin;

import com.keel.server.insight.SavedTraces;
import com.keel.server.integration.audit.AuditStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EchoProbeTest {
    @Test void invokeRecordsATraceAndAnAuditEvent() {
        var saved = new ArrayList<String>();
        var actions = new ArrayList<String>();
        var probe = new EchoProbe(new SavedTraces() {
            @Override
            public void save(String agent, String env, String traceId, String question, int durationMs) {
                saved.add(agent + ":" + question);
            }

            @Override
            public Map<String, Object> list(int page, int size, String agent) {
                return Map.of();
            }

            @Override
            public Map<String, Object> detail(String traceId) {
                return null;
            }
        }, new AuditStore() {
            @Override
            public void append(String agent, String env, String action, String risk, String decision, String resource, String traceId) {
                actions.add(action + ":" + risk);
            }

            @Override
            public Map<String, Object> page(String agent, String risk, String env, int page, int size) {
                return Map.of();
            }

            @Override
            public Map<String, Object> verify(String agent) {
                return Map.of();
            }
        });
        assertThat(probe.invoke("echo", "dev")).contains("event: final");
        assertThat(saved).containsExactly("echo:探针");
        assertThat(actions).containsExactly("invoke:low");
    }
}
