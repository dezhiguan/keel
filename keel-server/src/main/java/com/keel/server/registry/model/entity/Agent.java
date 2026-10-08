package com.keel.server.registry.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.keel.server.registry.model.enums.AgentStatus;

import java.time.OffsetDateTime;

@TableName("agent")
public class Agent {
    public static final String KIND_AGENT = "AGENT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String displayName;
    private String kind;
    private String runtime;
    private String language;
    private String ownerOrg;
    private String ownerUser;
    private AgentStatus status;
    private String liveness;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String layer;
    private String devflowJobId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getOwnerOrg() { return ownerOrg; }
    public void setOwnerOrg(String ownerOrg) { this.ownerOrg = ownerOrg; }
    public String getOwnerUser() { return ownerUser; }
    public void setOwnerUser(String ownerUser) { this.ownerUser = ownerUser; }
    public AgentStatus getStatus() { return status; }
    public void setStatus(AgentStatus status) { this.status = status; }
    public String getLiveness() { return liveness; }
    public void setLiveness(String liveness) { this.liveness = liveness; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public String getDevflowJobId() { return devflowJobId; }
    public void setDevflowJobId(String devflowJobId) { this.devflowJobId = devflowJobId; }
}
