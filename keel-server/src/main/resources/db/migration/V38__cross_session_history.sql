-- Explicit personal-assistant history policy. No tool grants are implied.
CREATE TABLE agent_history_policy (
    agent_name VARCHAR(64) NOT NULL,
    env VARCHAR(16) NOT NULL,
    retention_days INTEGER CHECK (retention_days > 0),
    PRIMARY KEY(agent_name, env)
);
INSERT INTO agent_history_policy(agent_name,env,retention_days) VALUES ('keel-assistant','staging',NULL);
-- Preserve only originals still available under the previous policy.
UPDATE session_message m SET expires_at='infinity'::timestamptz
FROM agent_session s WHERE s.id=m.session_id AND s.agent_name=m.agent_name
AND s.owner_user=m.owner_user AND s.agent_name='keel-assistant' AND s.env='staging'
AND m.expires_at>now();
CREATE INDEX ix_session_message_owner_history ON session_message(agent_name,owner_user,id DESC);
INSERT INTO tool(name,scope,owner_org,provider,status)
VALUES ('keel.history.search','SHARED','平台组','keel','ONLINE') ON CONFLICT(name) DO NOTHING;
INSERT INTO tool_version(tool_name,version,schema_json,description,access,risk,breaking)
VALUES ('keel.history.search','1.0.0','{"type":"object"}',
'检索可信登录用户自己的其他会话原文，需当前会话签名授权','READ','LOW',false)
ON CONFLICT DO NOTHING;
