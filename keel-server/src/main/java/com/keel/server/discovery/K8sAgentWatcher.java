package com.keel.server.discovery;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Records labeled pods. Classification stays in {@link ReconcileJob}. */
public class K8sAgentWatcher {
    private final JdbcTemplate jdbc;
    private final Set<String> knownAgents;
    private final Map<String, LivePod> live = new ConcurrentHashMap<>();

    public K8sAgentWatcher(JdbcTemplate jdbc, Set<String> knownAgents) {
        this.jdbc = jdbc;
        this.knownAgents = knownAgents;
    }

    public void start(KubernetesClient client) {
        try {
            inform(client, "keel.io/agent");
            inform(client, "keel.io/service");
        } catch (RuntimeException ignored) {
            // A missing cluster must not stop keel-server. The next process start tries again.
        }
    }

    public void observe(Pod pod, boolean deleted) {
        var labels = pod.getMetadata() == null || pod.getMetadata().getLabels() == null
                ? Map.<String, String>of() : pod.getMetadata().getLabels();
        var name = labels.get("keel.io/agent");
        if (name == null || name.isBlank()) {
            name = labels.get("keel.io/service");
        }
        if (name == null || name.isBlank()) {
            return;
        }
        var env = labels.getOrDefault("keel.io/env", "dev");
        if (!env.equals("dev") && !env.equals("staging") && !env.equals("prod")) {
            return;
        }
        var version = labels.get("keel.io/version");
        if (version != null && version.isBlank()) {
            version = null;
        }
        var id = pod.getMetadata().getUid() == null ? pod.getMetadata().getName() : pod.getMetadata().getUid();
        var key = name + "/" + env + "/" + id;
        if (deleted) {
            live.remove(key);
            if (jdbc != null && registered(name)) {
                jdbc.update("DELETE FROM agent_instance WHERE agent_name = ? AND env = ? AND instance_id = ?", name, env, id);
            }
            return;
        }
        live.put(key, new LivePod(name, env, id, version, ready(pod), registered(name)));
        if (jdbc != null && registered(name)) {
            jdbc.update("""
                    INSERT INTO agent_instance (agent_name, env, instance_id, source, version, ready, last_seen_at)
                    VALUES (?, ?, ?, 'k8s', ?, ?, now())
                    ON CONFLICT (agent_name, env, instance_id)
                    DO UPDATE SET source = 'k8s', version = EXCLUDED.version, ready = EXCLUDED.ready, last_seen_at = now()
                    """, name, env, id, version, ready(pod));
        }
    }

    public List<LivePod> live() {
        return new ArrayList<>(live.values());
    }

    private boolean registered(String name) {
        if (knownAgents != null) {
            return knownAgents.contains(name);
        }
        if (jdbc == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM agent WHERE name = ?", Integer.class, name);
        return count != null && count > 0;
    }

    private void inform(KubernetesClient client, String label) {
        client.pods().inAnyNamespace().withLabel(label).inform(new ResourceEventHandler<Pod>() {
            @Override public void onAdd(Pod pod) { observe(pod, false); }
            @Override public void onUpdate(Pod oldPod, Pod pod) { observe(pod, false); }
            @Override public void onDelete(Pod pod, boolean deletedFinalStateUnknown) { observe(pod, true); }
        });
    }

    private static boolean ready(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    public record LivePod(String name, String env, String instanceId, String version, boolean ready, boolean registered) {}
}
