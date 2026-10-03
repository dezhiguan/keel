package com.keel.starter.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the environment (for example {@code KEEL_LLM_BASE_URL}).
 * Fields have no defaults: a missing secret must stay missing.
 */
@ConfigurationProperties(prefix = "keel")
public class KeelProperties {
    private String llmBaseUrl;
    private String llmKey;
    private String ragforgeUrl;
    private String ragforgeToken;
    private String auditUrl;
    private String auditToken;
    private String auditSpoolPath;
    private String env;

    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String llmBaseUrl) { this.llmBaseUrl = llmBaseUrl; }
    public String getLlmKey() { return llmKey; }
    public void setLlmKey(String llmKey) { this.llmKey = llmKey; }
    public String getRagforgeUrl() { return ragforgeUrl; }
    public void setRagforgeUrl(String ragforgeUrl) { this.ragforgeUrl = ragforgeUrl; }
    public String getRagforgeToken() { return ragforgeToken; }
    public void setRagforgeToken(String ragforgeToken) { this.ragforgeToken = ragforgeToken; }
    public String getAuditUrl() { return auditUrl; }
    public void setAuditUrl(String auditUrl) { this.auditUrl = auditUrl; }
    public String getAuditToken() { return auditToken; }
    public void setAuditToken(String auditToken) { this.auditToken = auditToken; }
    public String getAuditSpoolPath() { return auditSpoolPath; }
    public void setAuditSpoolPath(String auditSpoolPath) { this.auditSpoolPath = auditSpoolPath; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
}
