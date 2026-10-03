package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/** Kubernetes Secret keel-{agent}. The cluster call is filled in by P1-8. */
@Service
public class SecretWriter implements ProvisionStep {
    private final ConcurrentHashMap<String, String> privateKeys = new ConcurrentHashMap<>();

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

    @Override
    public void provision(String agent, String env, JsonNode manifest) {
    }

    @Override
    public void revoke(String agent, String env) {
        privateKeys.remove(agent);
    }
}
