package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.JsonNode;

/** Identifiers provisioning actually creates. The ledger used to store the agent name for every type. */
public final class ResourceNames {
    private ResourceNames() {
    }

    public static String canonical(String type, String agent, String env, JsonNode manifest) {
        return switch (type == null ? "" : type) {
            case "litellm_key" -> env == null || env.isBlank() ? agent : agent + "-" + env;
            case "secret" -> "keel-" + agent;
            case "dataset" -> dataset(manifest, agent);
            default -> agent;
        };
    }

    /**
     * Prefer an id that is already specific. Rows written before that fix stored the agent name,
     * which is only the real id for the OAuth client.
     */
    public static String display(String type, String agent, String env, String stored, JsonNode manifest) {
        if (stored != null && !stored.isBlank() && !stored.equals(agent)) {
            return stored;
        }
        var canonical = canonical(type, agent, env, manifest);
        return canonical == null || canonical.isBlank() ? stored : canonical;
    }

    private static String dataset(JsonNode manifest, String agent) {
        if (manifest == null) {
            return agent;
        }
        var name = manifest.path("spec").path("eval").path("dataset").asText("");
        return name.isBlank() ? agent : name;
    }
}
