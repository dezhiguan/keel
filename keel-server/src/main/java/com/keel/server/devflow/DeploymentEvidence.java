package com.keel.server.devflow;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.Map;

/** Confirms that an immutable candidate image finished rolling out in the internal agents namespace. */
final class DeploymentEvidence {
    private DeploymentEvidence() {}

    static boolean ready(KubernetesClient cluster, String agent, String env, String sha, String image) {
        if (cluster == null || agent == null || !agent.matches("[a-z][a-z0-9-]{1,38}[a-z0-9]")
                || !"staging".equals(env) && !"prod".equals(env)
                || sha == null || !sha.matches("[0-9a-f]{40}")
                || image == null || !image.matches("[^\\s]+@sha256:[0-9a-f]{64}")) return false;
        var deployment = cluster.apps().deployments().inNamespace("agents")
                .withName(agent + "-" + env).get();
        return ready(deployment, agent, env, sha, image);
    }

    static boolean ready(Deployment deployment, String agent, String env, String sha, String image) {
        if (deployment == null || deployment.getMetadata() == null || deployment.getSpec() == null
                || deployment.getStatus() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) return false;
        var metadata = deployment.getMetadata();
        var template = deployment.getSpec().getTemplate();
        if (!labelsMatch(metadata.getLabels(), agent, env, sha) || template.getMetadata() == null
                || !labelsMatch(template.getMetadata().getLabels(), agent, env, sha)) return false;
        var desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
        var status = deployment.getStatus();
        if (desired < 1 || metadata.getGeneration() == null || status.getObservedGeneration() == null
                || status.getObservedGeneration() < metadata.getGeneration()
                || status.getReadyReplicas() == null || status.getReadyReplicas() < desired
                || status.getUpdatedReplicas() == null || status.getUpdatedReplicas() < desired
                || status.getAvailableReplicas() == null || status.getAvailableReplicas() < desired) return false;
        return template.getSpec().getContainers() != null
                && template.getSpec().getContainers().stream().anyMatch(container -> image.equals(container.getImage()));
    }

    private static boolean labelsMatch(Map<String, String> labels, String agent, String env, String sha) {
        return labels != null && agent.equals(labels.get("keel.io/agent"))
                && env.equals(labels.get("keel.io/env")) && sha.equals(labels.get("keel.io/commit"));
    }
}
