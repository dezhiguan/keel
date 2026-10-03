package com.keel.server.registry.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("agent_version")
public class AgentVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentName;
    private String version;
    private String env;
    private String manifestJson;
    private String manifestHash;
    private String image;
    private String gateRunId;
    private String releasedBy;
    private OffsetDateTime releasedAt;

    public void setId(Long id) { this.id = id; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public void setVersion(String version) { this.version = version; }
    public void setEnv(String env) { this.env = env; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public void setManifestHash(String manifestHash) { this.manifestHash = manifestHash; }
    public void setImage(String image) { this.image = image; }
    public void setGateRunId(String gateRunId) { this.gateRunId = gateRunId; }
    public void setReleasedBy(String releasedBy) { this.releasedBy = releasedBy; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
    public Long getId() { return id; }
    public String getAgentName() { return agentName; }
    public String getVersion() { return version; }
    public String getEnv() { return env; }
    public String getManifestJson() { return manifestJson; }
    public String getManifestHash() { return manifestHash; }
    public String getImage() { return image; }
    public String getGateRunId() { return gateRunId; }
    public String getReleasedBy() { return releasedBy; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
}
