package com.keel.server.provisioning;

import org.springframework.stereotype.Service;

/** Dataset import. The Langfuse call is filled in by P1-8. */
@Service
public class LangfuseProvisioner implements ProvisionStep {
    @Override
    public String resourceType() {
        return "dataset";
    }

    @Override
    public void provision(String agent, String env, com.fasterxml.jackson.databind.JsonNode manifest) {
    }

    @Override
    public void revoke(String agent, String env) {
    }
}
