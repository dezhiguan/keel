package com.keel.server.devflow;

import com.keel.server.provisioning.AgentKeys;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** A dedicated CI key is never mounted into generated employee containers. */
@Component
public class DevflowCiIdentity {
    private final KubernetesClient cluster;
    private final AgentKeys keys;
    private final String name;
    public DevflowCiIdentity(KubernetesClient cluster, AgentKeys keys, @Value("${keel.ci.client-id:}") String name) {
        this.cluster = cluster; this.keys = keys; this.name = name;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ensure() {
        if (name.isBlank() || keys.find(name) != null) return;
        var material = keys.generate(name);
        var secret = new SecretBuilder().withNewMetadata().withNamespace("agents").withName("keel-" + name)
                .addToLabels("keel.io/purpose", "trusted-ci").endMetadata()
                .withData(Map.of("KEEL_CLIENT_PRIVATE_KEY", Base64.getEncoder().encodeToString(
                        material.privatePem().getBytes(StandardCharsets.UTF_8)))).build();
        try { cluster.secrets().inNamespace("agents").resource(secret).create(); }
        catch (KubernetesClientException error) { if (error.getCode() != 409) throw error; }
    }
}
