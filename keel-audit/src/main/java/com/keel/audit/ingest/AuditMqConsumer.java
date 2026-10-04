package com.keel.audit.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.keel.audit.chain.HashChainService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Same-agent events stay on one thread so the chain keeps send order. */
public class AuditMqConsumer {
    public static final String TOPIC = "keel-audit-events";
    private final HashChainService chain;
    private final ConcurrentHashMap<String, ExecutorService> agents = new ConcurrentHashMap<>();

    public AuditMqConsumer(HashChainService chain) {
        this.chain = chain;
    }

    public void accept(JsonNode event) {
        String agent = event.path("agent").asText("");
        agents.computeIfAbsent(agent, key -> Executors.newSingleThreadExecutor()).execute(() -> chain.append(event, false));
    }

    public void onMessage(JsonNode event) {
        chain.append(event, false);
    }
}
