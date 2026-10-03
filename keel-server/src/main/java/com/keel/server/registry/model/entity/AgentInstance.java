package com.keel.server.registry.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("agent_instance")
public class AgentInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentName;
    private String env;
    private String instanceId;
    private String source;
    private String version;
    private boolean ready;
    private OffsetDateTime lastSeenAt;

    public void setId(Long id) { this.id = id; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public void setEnv(String env) { this.env = env; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public void setSource(String source) { this.source = source; }
    public void setVersion(String version) { this.version = version; }
    public void setReady(boolean ready) { this.ready = ready; }
    public void setLastSeenAt(OffsetDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public String getAgentName() { return agentName; }
    public String getEnv() { return env; }
    public String getInstanceId() { return instanceId; }
    public String getSource() { return source; }
    public String getVersion() { return version; }
    public boolean isReady() { return ready; }
    public OffsetDateTime getLastSeenAt() { return lastSeenAt; }
}
