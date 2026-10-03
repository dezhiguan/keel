package com.keel.server.provisioning;

import org.springframework.stereotype.Service;

/** Virtual key. The LiteLLM call is filled in by P1-8. */
@Service
public class LiteLlmProvisioner implements ProvisionStep {
    @Override
    public String resourceType() {
        return "litellm_key";
    }

    @Override
    public void provision(String agent, String env, com.fasterxml.jackson.databind.JsonNode manifest) {
    }

    @Override
    public void revoke(String agent, String env) {
    }
}
