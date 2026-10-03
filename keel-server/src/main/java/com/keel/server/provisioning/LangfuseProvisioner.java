package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.server.integration.langfuse.LangfuseClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
        client.importSeed(dataset, seedFile);
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
