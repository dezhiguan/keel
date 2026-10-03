package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;

/** One external resource created during registration. P1-7 and P1-8 fill in the calls. */
public interface ProvisionStep {
    String resourceType();

    void provision(String agent, String env, JsonNode manifest);

    void revoke(String agent, String env);
}
