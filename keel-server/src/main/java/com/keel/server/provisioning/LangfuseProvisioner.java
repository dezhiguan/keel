package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LangfuseProvisioner implements ProvisionStep {
    private final LangfuseClient client;
    private final Path seedFile;
    private final ConcurrentHashMap<String, String> datasets = new ConcurrentHashMap<>();

    public LangfuseProvisioner(LangfuseClient client, @Value("${KEEL_EVAL_SEED:evals/seed.jsonl}") String seedFile) {
        this.client = client;
        this.seedFile = Path.of(seedFile);
    }

    @Override
    public String resourceType() {
        return "dataset";
    }

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
        var dataset = manifest.path("spec").path("eval").path("dataset").asText("");
        if (dataset.isBlank()) {
            throw new IllegalStateException("dataset 缺失");
        }
        Path source = seedFile;
        Path bundled = null;
        try {
            if (!Files.isRegularFile(source) && "evals/seed.jsonl".equals(source.toString())) {
                try (InputStream input = getClass().getResourceAsStream("/evals/seed.jsonl")) {
                    if (input == null) throw new IllegalStateException("缺少内置评测用例 evals/seed.jsonl");
                    bundled = Files.createTempFile("keel-eval-seed-", ".jsonl");
                    Files.copy(input, bundled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    source = bundled;
                }
            }
            client.importSeed(dataset, source);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("读取评测用例失败", error);
        } finally {
            if (bundled != null) {
                try { Files.deleteIfExists(bundled); } catch (java.io.IOException ignored) { }
            }
        }
        datasets.put(agent + ":" + env, dataset);
    }

    @Override
    public void revoke(String agent, String env) {
        var dataset = datasets.remove(agent + ":" + env);
        if (dataset != null) {
            client.deleteDataset(dataset);
        }
    }
}
