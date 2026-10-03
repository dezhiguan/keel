package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SecretWriter implements ProvisionStep {
    static final String NAMESPACE = "agents";

    private final KubernetesClient client;
    private final String langfusePublicKey;
    private final String langfuseSecretKey;
    private final String langfuseHost;
    private final String llmBaseUrl;
    private final ConcurrentHashMap<String, String> privateKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> llmKeys = new ConcurrentHashMap<>();

    public SecretWriter(KubernetesClient client,
                        @Value("${LANGFUSE_PUBLIC_KEY:}") String langfusePublicKey,
                        @Value("${LANGFUSE_SECRET_KEY:}") String langfuseSecretKey,
                        @Value("${LANGFUSE_HOST:}") String langfuseHost,
                        @Value("${KEEL_LLM_BASE_URL:}") String llmBaseUrl) {
        this.client = client;
        this.langfusePublicKey = langfusePublicKey == null ? "" : langfusePublicKey;
        this.langfuseSecretKey = langfuseSecretKey == null ? "" : langfuseSecretKey;
        this.langfuseHost = langfuseHost == null ? "" : langfuseHost;
        this.llmBaseUrl = llmBaseUrl == null ? "" : llmBaseUrl;
    }

    @Override
    public String resourceType() {
        return "secret";
    }

    public void rememberPrivateKey(String agent, String pem) {
        privateKeys.put(agent, pem);
    }

    public String privateKeyFor(String agent) {
        return privateKeys.get(agent);
    }

    public void rememberLlmKey(String agent, String key) {
        llmKeys.put(agent, key);
    }

    public void forgetLlmKey(String agent) {
        llmKeys.remove(agent);
    }

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
        var privateKey = privateKeys.get(agent);
        var llmKey = llmKeys.get(agent);
        if (privateKey == null || llmKey == null || langfusePublicKey.isBlank() || langfuseSecretKey.isBlank()
                || langfuseHost.isBlank() || llmBaseUrl.isBlank()) {
            throw new IllegalStateException("Secret 材料不齐");
        }
        var data = new LinkedHashMap<String, String>();
        data.put("KEEL_CLIENT_PRIVATE_KEY", encode(privateKey));
        data.put("KEEL_LLM_KEY", encode(llmKey));
        data.put("KEEL_LLM_BASE_URL", encode(llmBaseUrl));
        data.put("LANGFUSE_PUBLIC_KEY", encode(langfusePublicKey));
        data.put("LANGFUSE_SECRET_KEY", encode(langfuseSecretKey));
        data.put("LANGFUSE_HOST", encode(langfuseHost));
        Secret secret = new SecretBuilder()
                .withNewMetadata().withName("keel-" + agent).withNamespace(NAMESPACE).endMetadata()
                .withData(data)
                .build();
        client.secrets().inNamespace(NAMESPACE).resource(secret).createOrReplace();
    }

    @Override
    public void revoke(String agent, String env) {
        privateKeys.remove(agent);
        llmKeys.remove(agent);
        client.secrets().inNamespace(NAMESPACE).withName("keel-" + agent).delete();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
