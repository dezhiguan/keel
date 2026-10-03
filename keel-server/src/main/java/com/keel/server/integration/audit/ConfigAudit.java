package com.keel.server.integration.audit;

/** Synchronous config.change audit. A failure must abort registration. */
public interface ConfigAudit {
    void record(String agent, String env);
}
