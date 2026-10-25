package com.keel.server.registry.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Internal runtime credentials are independent for every application. */
@Component
public class AgentBridgeCredentials {
    private final String askdb;
    private final String meta;
    private final String career;

    public AgentBridgeCredentials(@Value("${KEEL_ASKDB_TOKEN:}") String askdb,
                                  @Value("${KEEL_META_AGENT_TOKEN:}") String meta,
                                  @Value("${KEEL_CAREERMATE_TOKEN:}") String career) {
        this.askdb = askdb; this.meta = meta; this.career = career;
    }

    public String token(String agent) {
        return switch (agent) {
            case "askdb" -> askdb;
            case "meta-agent" -> meta;
            case "careermate", "careermate-keel" -> career;
            default -> null;
        };
    }
}
