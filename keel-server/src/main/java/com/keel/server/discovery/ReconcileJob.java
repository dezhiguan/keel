package com.keel.server.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class ReconcileJob {
    private final FindingBook findings;
    private final TrafficProbe traffic;
    private final K8sAgentWatcher watcher;
    private final JdbcTemplate jdbc;

    public ReconcileJob(FindingBook findings, TrafficProbe traffic, K8sAgentWatcher watcher, JdbcTemplate jdbc) {
        this.findings = findings;
        this.traffic = traffic;
        this.watcher = watcher;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void scheduled() {
        if (jdbc == null) {
            return;
        }
        var now = Instant.now();
        var agents = new HashMap<String, String>();
        jdbc.query("SELECT name, status FROM agent", rs -> {
            agents.put(rs.getString("name"), rs.getString("status"));
        });
        var versions = new HashMap<String, String>();
        jdbc.query("""
                SELECT DISTINCT ON (agent_name, env) agent_name, env, version
                FROM agent_version ORDER BY agent_name, env, released_at DESC
                """, rs -> {
            versions.put(rs.getString("agent_name") + "/" + rs.getString("env"), rs.getString("version"));
        });
        var liveReady = new HashSet<String>();
        if (watcher != null) {
            for (var pod : watcher.live()) {
                if (pod.ready()) {
                    liveReady.add(pod.name() + "/" + pod.env() + "/" + pod.instanceId());
                }
            }
        }
        var seen = new HashMap<String, Instance>();
        var sightings = new HashMap<String, List<AgentLiveness.Sighting>>();
        var seenInstances = new HashSet<String>();
        jdbc.query("SELECT agent_name, env, instance_id, source, ready, version, last_seen_at FROM agent_instance", rs -> {
            var name = rs.getString("agent_name");
            var env = rs.getString("env");
            var key = name + "/" + env;
            var instanceId = rs.getString("instance_id");
            var current = seen.get(key);
            var ready = rs.getBoolean("ready");
            var seenAt = rs.getObject("last_seen_at", OffsetDateTime.class).toInstant();
            var version = rs.getString("version");
            if (current == null || ready || seenAt.isAfter(current.lastSeen)) {
                seen.put(key, new Instance(ready || (current != null && current.ready), version == null && current != null ? current.version : version, seenAt));
            }
            var instanceKey = key + "/" + instanceId;
            seenInstances.add(instanceKey);
            var live = watcher == null ? null : liveReady.contains(instanceKey);
            sightings.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .add(new AgentLiveness.Sighting(AgentLiveness.serving(rs.getString("source"), ready, seenAt, now, live)));
        });
        if (watcher != null) {
            for (var pod : watcher.live()) {
                if (!pod.registered() || !agents.containsKey(pod.name())) {
                    continue;
                }
                var instanceKey = pod.name() + "/" + pod.env() + "/" + pod.instanceId();
                if (seenInstances.contains(instanceKey)) {
                    continue;
                }
                sightings.computeIfAbsent(pod.name(), ignored -> new ArrayList<>())
                        .add(new AgentLiveness.Sighting(pod.ready()));
            }
        }
        for (var agent : agents.entrySet()) {
            var considered = false;
            for (var env : new String[] {"dev", "test", "staging", "prod"}) {
                var version = versions.get(agent.getKey() + "/" + env);
                var instance = seen.get(agent.getKey() + "/" + env);
                if (version == null && instance == null) {
                    continue;
                }
                considered = true;
                apply(agent.getKey(), env, agent.getValue(), true, instance, version, now);
            }
            if (!considered) {
                continue;
            }
            var next = AgentLiveness.next(agent.getValue(), sightings.getOrDefault(agent.getKey(), List.of()));
            if (!next.equals(agent.getValue())) {
                jdbc.update("UPDATE agent SET status = ?, updated_at = now() WHERE name = ? AND status = ?",
                        next, agent.getKey(), agent.getValue());
            }
        }
        if (watcher != null) {
            for (var pod : watcher.live()) {
                if (!pod.registered() && !agents.containsKey(pod.name())) {
                    apply(pod.name(), pod.env(), null, false,
                            new Instance(pod.ready(), pod.version(), now), null, now);
                }
            }
        }
    }

    public void apply(String agent, String env, ReconcileRules.Input input) {
        var kind = ReconcileRules.classify(input).map(Enum::name).orElse(null);
        findings.sync(agent, env, kind, "{\"status\":\"" + (input.status() == null ? "" : input.status()) + "\"}", input.now());
    }

    private void apply(String agent, String env, String status, boolean registered, Instance instance, String registeredVersion, Instant now) {
        var running = instance != null;
        var langfuse = traffic == null ? null : traffic.langfuse(now);
        var gateway = traffic == null ? null : traffic.gateway(agent, env, now);
        var findingStatus = "OFFLINE".equals(status) || "DEGRADED".equals(status) ? "ONLINE" : status;
        apply(agent, env, new ReconcileRules.Input(
                findingStatus,
                registered,
                running,
                running && instance.ready,
                running ? instance.lastSeen : null,
                now,
                running ? instance.version : null,
                registeredVersion,
                langfuse,
                gateway));
    }

    private record Instance(boolean ready, String version, Instant lastSeen) {}
}
