package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SecretWriter implements ProvisionStep {
    static final String NAMESPACE = "agents";

    private final KubernetesClient client;
    private final String langfusePublicKey;
    private final String langfuseSecretKey;
    private final String langfuseHost;
    private final String llmBaseUrl;
    private final RagForgeAccess ragForge;
    @Value("${KEEL_SERVER_URL:}")
    private String serverUrl;
    @Value("${KEEL_SERVICE_AUDIENCE:}")
    private String serviceAudience;
    private final ConcurrentHashMap<String, String> privateKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> llmKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RagForgeAccess.Issued> pendingRag = new ConcurrentHashMap<>();

    @Autowired
    public SecretWriter(KubernetesClient client,
                        @Value("${LANGFUSE_PUBLIC_KEY:}") String langfusePublicKey,
                        @Value("${LANGFUSE_SECRET_KEY:}") String langfuseSecretKey,
                        @Value("${LANGFUSE_HOST:}") String langfuseHost,
                        @Value("${KEEL_LLM_BASE_URL:}") String llmBaseUrl,
                        RagForgeAccess ragForge) {
        this.client = client;
        this.langfusePublicKey = langfusePublicKey == null ? "" : langfusePublicKey;
        this.langfuseSecretKey = langfuseSecretKey == null ? "" : langfuseSecretKey;
        this.langfuseHost = langfuseHost == null ? "" : langfuseHost;
        this.llmBaseUrl = llmBaseUrl == null ? "" : llmBaseUrl;
        this.ragForge = ragForge;
    }

    public SecretWriter(KubernetesClient client, String langfusePublicKey, String langfuseSecretKey,
                        String langfuseHost, String llmBaseUrl) {
        this(client, langfusePublicKey, langfuseSecretKey, langfuseHost, llmBaseUrl, null);
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

    /** Called before provisioning when the manifest declares knowledge bases. */
    public void prepareKnowledgeKey(String agent, String env, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        if (ragForge == null) {
            throw new IllegalStateException("知识库密钥发放没有接上");
        }
        pendingRag.put(agent, ragForge.issue(agent, env, names));
    }

    /** Drops a key that was issued but never written, for example when a later check refuses registration. */
    public void discardKnowledgeKey(String agent) {
        var issued = pendingRag.remove(agent);
        if (issued != null && ragForge != null) {
            ragForge.revoke(issued.id());
        }
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
        data.put("KEEL_ENV", encode(env));
        if (serverUrl != null && !serverUrl.isBlank()) data.put("KEEL_SERVER_URL", encode(serverUrl));
        if (serviceAudience != null && !serviceAudience.isBlank()) data.put("KEEL_SERVICE_AUDIENCE", encode(serviceAudience));
        var knowledge = knowledgeNames(manifest);
        var rag = pendingRag.get(agent);
        if (!knowledge.isEmpty()) {
            if (rag == null) {
                throw new IllegalStateException("知识库密钥还没有发放");
            }
            data.put("KEEL_RAGFORGE_URL", encode(rag.url()));
            data.put("KEEL_RAGFORGE_TOKEN", encode(rag.token()));
            data.put("KEEL_RAGFORGE_KEY_ID", encode(Long.toString(rag.id())));
            data.put("KEEL_RAGFORGE_KB_IDS", encode(rag.kbIds()));
        }
        Secret secret = new SecretBuilder()
                .withNewMetadata().withName("keel-" + agent).withNamespace(NAMESPACE).endMetadata()
                .withData(data)
                .build();
        client.secrets().inNamespace(NAMESPACE).resource(secret).createOrReplace();
        pendingRag.remove(agent);
    }

    @Override
    public void revoke(String agent, String env) {
        privateKeys.remove(agent);
        llmKeys.remove(agent);
        var rag = pendingRag.remove(agent);
        long keyId = rag == null ? storedKeyId(agent) : rag.id();
        if (keyId > 0 && ragForge != null) {
            ragForge.revoke(keyId);
        }
        client.secrets().inNamespace(NAMESPACE).withName("keel-" + agent).delete();
    }

    /** Token and knowledge-base ids stored for this agent. Null when this agent has no key. */
    public RagCredential knowledgeCredential(String agent) {
        try {
            var secret = client.secrets().inNamespace(NAMESPACE).withName("keel-" + agent).get();
            if (secret == null || secret.getData() == null) {
                return null;
            }
            var token = decode(secret.getData().get("KEEL_RAGFORGE_TOKEN"));
            var ids = decode(secret.getData().get("KEEL_RAGFORGE_KB_IDS"));
            if (token.isBlank()) {
                return null;
            }
            return new RagCredential(token, ids);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record RagCredential(String token, String kbIds) {}

    private static String decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim();
    }

    private long storedKeyId(String agent) {
        try {
            var secret = client.secrets().inNamespace(NAMESPACE).withName("keel-" + agent).get();
            if (secret == null || secret.getData() == null) {
                return 0;
            }
            var raw = secret.getData().get("KEEL_RAGFORGE_KEY_ID");
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            return Long.parseLong(new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String> knowledgeNames(JsonNode manifest) {
        var names = new ArrayList<String>();
        var knowledge = manifest == null ? null : manifest.path("spec").path("knowledge");
        if (knowledge != null && knowledge.isArray()) {
            knowledge.forEach(item -> {
                var name = item.path("kb").asText("").trim();
                if (!name.isBlank()) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
