package com.keel.server.provisioning;

import com.keel.server.integration.litellm.LiteLlmClient;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Gives the echo process a thin-gateway virtual key. Langfuse keys stay in the keel-server Secret. */
@Service
public class EchoCredentialWriter {
    static final String NAMESPACE = "keel-system";

    private final KubernetesClient kubernetes;
    private final LiteLlmClient gateway;

    public EchoCredentialWriter(KubernetesClient kubernetes, LiteLlmClient gateway) {
        this.kubernetes = kubernetes;
        this.gateway = gateway;
    }

    public void ensure(String agent) {
        var base = System.getenv("KEEL_LLM_BASE_URL");
        var admin = System.getenv("KEEL_LLM_ADMIN_KEY");
        if (base == null || base.isBlank() || admin == null || admin.isBlank()) {
            return;
        }
        var name = "keel-" + agent;
        var existing = kubernetes.secrets().inNamespace(NAMESPACE).withName(name).get();
        if (existing != null && existing.getData() != null && existing.getData().containsKey("KEEL_LLM_KEY")) {
            return;
        }
        var key = gateway.generate(agent + "-dev", List.of("qwen-plus"), List.of(), BigDecimal.valueOf(30), false);
        var data = new java.util.LinkedHashMap<String, String>();
        data.put("KEEL_LLM_KEY", encode(key));
        data.put("KEEL_LLM_BASE_URL", encode("http://keel-llm.keel-system.svc.cluster.local:8088"));
        copy(data, "LANGFUSE_PUBLIC_KEY");
        copy(data, "LANGFUSE_SECRET_KEY");
        var secret = new SecretBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withData(data)
                .build();
        kubernetes.secrets().inNamespace(NAMESPACE).resource(secret).createOrReplace();
    }

    private static void copy(java.util.Map<String, String> data, String name) {
        var value = System.getenv(name);
        if (value != null && !value.isBlank()) {
            data.put(name, encode(value));
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
