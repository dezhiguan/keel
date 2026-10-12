package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;

/** One external resource created during registration. P1-7 and P1-8 fill in the calls. */
public interface ProvisionStep {
    String resourceType();

    /** Id written to agent_resource. Defaults to the agent name, which is only right for the OAuth client. */
    default String externalId(String agent, String env, JsonNode manifest) {
        return ResourceNames.canonical(resourceType(), agent, env, manifest);
    }

    void provision(String agent, String env, JsonNode manifest);

    void revoke(String agent, String env);
}
