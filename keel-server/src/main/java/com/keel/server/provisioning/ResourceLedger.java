package com.keel.server.provisioning;

public interface ResourceLedger {
    void activate(String agent, String env, String type, String externalId);

    void mark(String agent, String env, String type, String status);

    boolean hasActive(String agent, String env);

    /** Resource types still ACTIVE for this agent and environment, newest provision first. */
    java.util.List<String> activeTypes(String agent, String env);
}
